package com.pocketrealm.importer

import com.pocketrealm.importer.inno.InnoVersion
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream

/**
 * Synthetic Inno Setup installer writer — the inverse of
 * `importer.inno.InnoSetupReader`, producing structurally valid 5.3.5
 * payloads (PE resource offset table, CRC-framed LZMA1 blocks, idsk slices,
 * solid or per-file chunks, optional x86 call filtering) from plain test
 * data. As everywhere in this fixture package, no Blizzard bytes are
 * involved: callers compose entries from the synthetic PE/MPQ stubs.
 */
object SyntheticInnoInstaller {

    data class FileSpec(
        val path: String,
        val bytes: ByteArray,
        val callFiltered: Boolean = false,
        val directoryConstant: String = "app",
    )

    class Builder {
        var appName: String = "Synthetic Client"
        var appVersion: String = "1.12.1"
        var version: Long = InnoVersion.V5_3_5
        var lzma1Chunks: Boolean = true
        /** Solid payloads share one chunk; otherwise one chunk per file. */
        var solid: Boolean = true
        var sliceBytes: Int = 64 shl 20
        var blockCompression: Boolean = true
        var encryptFirstEntry: Boolean = false
    }

    fun build(
        directory: File,
        files: List<FileSpec>,
        configure: Builder.() -> Unit = {},
    ): File {
        val options = Builder().apply(configure)
        require(options.version == InnoVersion.V5_3_5) {
            "SyntheticInnoInstaller only encodes the 5.3.5 layout; the knob stays for a future version matrix"
        }
        val prepared = files.mapIndexed { index, spec ->
            val transformed = if (spec.callFiltered) encodeCallFilter(spec.bytes) else spec.bytes
            PreparedFile(
                rawDestination = "{${spec.directoryConstant}}\\${spec.path.replace('/', '\\')}",
                transformed = transformed,
                md5 = MessageDigest.getInstance("MD5").digest(spec.bytes),
                callFiltered = spec.callFiltered,
                encrypted = options.encryptFirstEntry && index == 0,
            )
        }

        // One concatenated chunk (solid) or a chunk per file.
        val chunkStreams = if (options.solid) {
            listOf(prepared)
        } else {
            prepared.map { listOf(it) }
        }.map { members ->
            val payload = ByteArrayOutputStream()
            members.forEach { payload.write(it.transformed) }
            val raw = payload.toByteArray()
            val body = if (options.lzma1Chunks) lzma1Stream(raw) else raw
            val out = ByteArrayOutputStream()
            out.write(CHUNK_MAGIC)
            out.write(body)
            Chunk(out.toByteArray(), members)
        }

        // Slice the chunk bytes across setup-N.bin files.
        val allBytes = ByteArrayOutputStream()
        chunkStreams.forEach { allBytes.write(it.bytes) }
        val stream = allBytes.toByteArray()
        // Slice start positions within the concatenated chunk-stream bytes.
        val sliceDataStarts = ArrayList<Long>()
        val slices = ArrayList<ByteArray>()
        var position = 0
        while (position < stream.size || slices.isEmpty()) {
            sliceDataStarts.add(position.toLong())
            val take = minOf(options.sliceBytes, stream.size - position)
            slices.add(stream.copyOfRange(position, position + take))
            position += take
            if (position >= stream.size) break
        }
        fun sliceFor(dataPosition: Long): Int = sliceDataStarts.indexOfLast { dataPosition >= it }

        val dataEntries = ArrayList<DataEntry>()
        val fileEntries = ArrayList<FileEntry>()
        var runningGlobal = 0L // position within the concatenated chunk-stream bytes
        chunkStreams.forEach { chunk ->
            val firstSlice = sliceFor(runningGlobal)
            val lastSlice = sliceFor(runningGlobal + chunk.bytes.size - 1)
            // The real format stores per-slice absolute file offsets (the
            // 12-byte slice header included), not concatenated-stream positions.
            val chunkOffset = SLICE_HEADER_BYTES + runningGlobal - sliceDataStarts[firstSlice]
            var fileOffset = 0L
            chunk.members.forEach { file ->
                dataEntries.add(
                    DataEntry(
                        firstSlice = firstSlice,
                        lastSlice = lastSlice,
                        chunkOffset = chunkOffset,
                        fileOffset = fileOffset,
                        fileSize = file.transformed.size.toLong(),
                        chunkSize = chunk.bytes.size.toLong(),
                        md5 = file.md5,
                        callFiltered = file.callFiltered,
                        encrypted = file.encrypted,
                    ),
                )
                fileEntries.add(FileEntry(file.rawDestination, dataEntries.size - 1))
                fileOffset += file.transformed.size
            }
            runningGlobal += chunk.bytes.size
        }

        directory.mkdirs()
        slices.forEachIndexed { index, bytes ->
            writeSliceFile(File(directory, "setup-${index + 1}.bin"), bytes)
        }
        val (primary, secondary) = HeaderWriter().write(
            options.appName, options.appVersion, "setup", fileEntries, dataEntries,
            options.blockCompression, options.lzma1Chunks,
        )
        writeSetupExe(File(directory, "setup.exe"), primary, secondary)
        return directory
    }

    // ---------------------------------------------------------------- pieces

    private class PreparedFile(
        val rawDestination: String,
        val transformed: ByteArray,
        val md5: ByteArray,
        val callFiltered: Boolean,
        val encrypted: Boolean,
    )

    private class Chunk(val bytes: ByteArray, val members: List<PreparedFile>)

    private class DataEntry(
        val firstSlice: Int,
        val lastSlice: Int,
        val chunkOffset: Long,
        val fileOffset: Long,
        val fileSize: Long,
        val chunkSize: Long,
        val md5: ByteArray,
        val callFiltered: Boolean,
        val encrypted: Boolean,
    )

    private class FileEntry(val destination: String, val location: Int)

    /** props + LE32 dict + LZMA1 stream (xz-java end marker is fine). */
    internal fun lzma1Stream(raw: ByteArray): ByteArray {
        val settings = LZMA2Options()
        settings.setDictSize(1 shl 16)
        val buffer = ByteArrayOutputStream()
        // xz-java's raw-LZMA1 constructor writes no properties header.
        buffer.write(byteArrayOf(0x5d, 0x00, 0x00, 0x01, 0x00)) // lc3/lp0/pb2, 64 KiB
        LZMAOutputStream(buffer, settings, true).use { it.write(raw) }
        return buffer.toByteArray()
    }

    /**
     * Inno's forward x86 CALL/JMP transform (5.2.0 – 5.3.8 variant: the high
     * address byte is never flipped). Mirror of the reader's inverse.
     */
    internal fun encodeCallFilter(bytes: ByteArray): ByteArray {
        val b = bytes.copyOf()
        var i = 0
        while (i + 4 < b.size) {
            if (b[i] == 0xe8.toByte() || b[i] == 0xe9.toByte()) {
                if ((0x10000 - (i % 0x10000)) >= 5) {
                    if (b[i + 4] == 0x00.toByte() || b[i + 4] == 0xff.toByte()) {
                        val address = (i + 5) and 0xffffff
                        var rel = (b[i + 1].toInt() and 0xff) or
                            ((b[i + 2].toInt() and 0xff) shl 8) or
                            ((b[i + 3].toInt() and 0xff) shl 16)
                        rel = (rel + address) and 0xffffff
                        b[i + 1] = rel.toByte()
                        b[i + 2] = (rel shr 8).toByte()
                        b[i + 3] = (rel shr 16).toByte()
                    }
                    i += 5
                    continue
                }
            }
            i++
        }
        return b
    }

    private fun writeSliceFile(target: File, data: ByteArray) {
        target.outputStream().use { out ->
            out.write("idska32\u001a".toByteArray(Charsets.US_ASCII))
            out.write(leInt(data.size + SLICE_HEADER_BYTES.toInt()))
            out.write(data)
        }
    }

    // ---------------------------------------------------------- setup.exe

    private const val HEADER_SIGNATURE = "Inno Setup Setup Data (5.3.5)"
    private val LOADER_MAGIC = "rDlPtS\u00cd\u00e6\u00d7{\u000b*".toByteArray(Charsets.ISO_8859_1)

    /**
     * File layout: PE headers (0x0-0x200), .rsrc tree (0x200-0x260), Inno
     * data (0x260: signature + both blocks), loader table last.
     */
    private fun writeSetupExe(target: File, primary: ByteArray, secondary: ByteArray) {
        val signature = ByteArray(64)
        HEADER_SIGNATURE.toByteArray(Charsets.US_ASCII).copyInto(signature)
        val innoData = signature + primary + secondary
        val headerOffset = INNO_OFFSET.toLong()
        val tableOffset = INNO_OFFSET + innoData.size

        val total = tableOffset + LOADER_TABLE_BYTES
        val file = ByteArray(total)
        val le = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN)

        // DOS + PE headers, one section covering everything from 0x200 on.
        file[0] = 'M'.code.toByte()
        file[1] = 'Z'.code.toByte()
        le.putInt(0x3c, 0x40)
        val pe = 0x40
        file[pe] = 0x50
        file[pe + 1] = 0x45
        le.putShort(pe + 6, 1) // section count
        le.putShort(pe + 20, 0xf0) // optional header size
        val section = pe + 24 + 0xf0
        ".rsrc".toByteArray(Charsets.US_ASCII).copyInto(file, section)
        val rsrcRaw = RSRC_OFFSET.toLong()
        val rsrcVa = 0x1000
        le.putInt(section + 8, total - RSRC_OFFSET) // virtual size
        le.putInt(section + 12, rsrcVa)
        le.putInt(section + 16, total - RSRC_OFFSET) // raw size
        le.putInt(section + 20, rsrcRaw.toInt())

        // Resource directory tree: type -> name 11111 -> language -> data.
        fun dirAt(offset: Int, entryId: Int, entryField: Int) {
            le.putInt(offset, 0) // characteristics
            le.putInt(offset + 4, 0) // timestamp
            le.putShort(offset + 8, 0)
            le.putShort(offset + 10, 0)
            le.putShort(offset + 12, 0) // named entries
            le.putShort(offset + 14, 1) // id entries
            le.putInt(offset + 16, entryId)
            le.putInt(offset + 20, entryField)
        }
        dirAt(RSRC_OFFSET, 10, 0x80000000.toInt() or 0x18) // type -> dir@0x218
        dirAt(0x218, 11111, 0x80000000.toInt() or 0x30) // name -> dir@0x230
        dirAt(0x230, 0x409, 0x48) // language -> data entry@0x248
        val tableRva = (rsrcVa + tableOffset - RSRC_OFFSET)
        le.putInt(0x248, tableRva)
        le.putInt(0x24c, LOADER_TABLE_BYTES)
        le.putInt(0x250, 0)
        le.putInt(0x254, 0)

        innoData.copyInto(file, INNO_OFFSET)
        buildLoaderTable(headerOffset).copyInto(file, tableOffset)
        target.writeBytes(file)
    }

    private fun buildLoaderTable(headerOffset: Long): ByteArray {
        val fields = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        fields.putInt(0) // skip
        fields.putInt(0) // exe offset
        fields.putInt(0) // exe uncompressed size
        fields.putInt(0) // exe CRC
        fields.putInt(headerOffset.toInt())
        fields.putInt(0) // data offset: external slices
        val fieldBytes = fields.array()
        val crc = CRC32()
        crc.update(LOADER_MAGIC)
        crc.update(leInt(1)) // revision
        crc.update(fieldBytes)
        return LOADER_MAGIC + leInt(1) + fieldBytes + leInt(crc.value.toInt())
    }

    // -------------------------------------------------------------- header

    private class HeaderWriter {
        fun write(
            appName: String,
            appVersionedName: String,
            baseFilename: String,
            files: List<FileEntry>,
            dataEntries: List<DataEntry>,
            compressBlocks: Boolean,
            lzma1Chunks: Boolean,
        ): Pair<ByteArray, ByteArray> {
            val w = LittleWriter()
            w.string(appName)
            w.string(appVersionedName)
            w.string("{synthetic-app-id}")
            w.string("") // copyright
            w.string("Synthetic") // publisher
            w.string("https://example.invalid/") // publisher url
            w.string("") // support phone (>= 5.1.13)
            w.string("https://example.invalid/") // support url
            w.string("https://example.invalid/") // updates url
            w.string("") // app version
            w.string("{pf}\\Synthetic") // default dir
            w.string("Synthetic") // default group
            w.string(baseFilename)
            w.string("{app}") // uninstall files dir
            w.string("") // uninstall name
            w.string("") // uninstall icon
            w.string("") // app mutex
            w.string("") // default user name
            w.string("") // default user organisation
            w.string("") // default serial
            w.string("") // readme
            w.string("") // contact
            w.string("") // comments
            w.string("") // modify path
            // >= 5.2.5: license, before, after, signature, compiled script.
            w.binary(ByteArray(0))
            w.binary(ByteArray(0))
            w.binary(ByteArray(0))
            w.binary(ByteArray(0))
            w.binary(ByteArray(0))
            repeat(32) { w.byte(0) } // lead bytes

            w.int(1) // languages
            w.int(0) // messages
            w.int(0) // permissions
            w.int(0) // types
            w.int(0) // components
            w.int(0) // tasks
            w.int(0) // directories
            w.int(files.size)
            w.int(dataEntries.size)
            w.int(0) // icons
            w.int(0) // ini
            w.int(0) // registry
            w.int(0) // deletes
            w.int(0) // uninstall deletes
            w.int(0) // runs
            w.int(0) // uninstall runs

            repeat(20) { w.byte(0) } // windows version range
            w.int(0) // back color
            w.int(0) // back color 2
            w.int(0) // image back color
            repeat(16) { w.byte(0) } // password MD5
            repeat(8) { w.byte(0) } // salt
            w.long(0) // extra disk space
            w.int(1) // slices per disk
            w.byte(0) // log mode
            w.byte(0) // dir exists warning
            w.byte(0) // privileges
            w.byte(0) // show language dialog
            w.byte(0) // language detection
            w.byte(3) // compression: lzma1
            w.byte(0) // architectures allowed
            w.byte(0) // architectures 64-bit
            w.int(0) // signed uninstaller size
            w.int(0) // signed uninstaller CRC
            w.byte(0) // disable dir page
            w.byte(0) // disable group page
            repeat(6) { w.byte(0) } // options bitfield (44 bits for 5.3.5)

            // Language entry (non-Unicode 5.x).
            w.string("english")
            w.string("English")
            w.string("") // dialog font
            w.string("") // title font
            w.string("") // welcome font
            w.string("") // copyright font
            w.string("") // data
            w.binary(ByteArray(0)) // license
            w.binary(ByteArray(0)) // before
            w.binary(ByteArray(0)) // after
            w.int(0x409) // language id
            w.int(0) // codepage -> 1252
            w.int(0) // dialog font size
            w.int(0) // title font size
            w.int(0) // welcome font size
            w.int(0) // copyright font size
            w.byte(0) // right to left

            files.forEach { file ->
                w.string(file.destination) // source
                w.string(file.destination) // destination
                w.string("") // install font name
                w.string("") // strong assembly name (>= 5.2.5)
                repeat(6) { w.string("") } // condition data
                repeat(20) { w.byte(0) } // windows version range
                w.int(file.location)
                w.int(0) // attributes
                w.long(0) // external size
                w.short(0) // permission
                repeat(4) { w.byte(0) } // file options (31 bits)
                w.byte(0) // user file
            }

            // Wizard images (>= 5.2.5: two images, no DLLs for LZMA1).
            w.binary(ByteArray(0))
            w.binary(ByteArray(0))

            val d = LittleWriter()
            dataEntries.forEach { entry ->
                d.int(entry.firstSlice)
                d.int(entry.lastSlice)
                d.int(entry.chunkOffset.toInt())
                d.long(entry.fileOffset)
                d.long(entry.fileSize)
                d.long(entry.chunkSize)
                d.raw(entry.md5)
                d.long(0) // filetime
                d.int(0) // file version ms
                d.int(0) // file version ls
                var bits = 0
                if (entry.callFiltered) bits = bits or (1 shl 4)
                if (entry.encrypted) bits = bits or (1 shl 6)
                if (lzma1Chunks) bits = bits or (1 shl 7) // chunk compressed
                d.byte(bits)
                d.byte(0) // 9 declared bits -> 2 bytes
            }
            return frameBlock(w.bytes(), compressBlocks) to frameBlock(d.bytes(), compressBlocks)
        }
    }

    /** Compressed block: header + [u32 crc][<=4096] frames over the stream. */
    internal fun frameBlock(payload: ByteArray, compress: Boolean): ByteArray {
        val stream = if (compress) lzma1Stream(payload) else payload
        val framed = ByteArrayOutputStream()
        var position = 0
        while (position < stream.size) {
            val take = minOf(4096, stream.size - position)
            val chunk = stream.copyOfRange(position, position + take)
            val crc = CRC32()
            crc.update(chunk)
            framed.write(leInt(crc.value.toInt()))
            framed.write(chunk)
            position += take
        }
        val stored = framed.toByteArray()
        val headerAndSize = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(stored.size).put(if (compress) 1 else 0).array()
        val crc = CRC32()
        crc.update(headerAndSize)
        return leInt(crc.value.toInt()) + headerAndSize + stored
    }

    private class LittleWriter {
        private val buffer = ByteArrayOutputStream()

        fun byte(value: Int) = buffer.write(value)

        fun short(value: Int) {
            buffer.write(value and 0xff)
            buffer.write((value shr 8) and 0xff)
        }

        fun int(value: Int) {
            buffer.write(value and 0xff)
            buffer.write((value shr 8) and 0xff)
            buffer.write((value shr 16) and 0xff)
            buffer.write((value shr 24) and 0xff)
        }

        fun long(value: Long) {
            var v = value
            repeat(8) {
                buffer.write((v and 0xff).toInt())
                v = v shr 8
            }
        }

        fun raw(bytes: ByteArray) = buffer.write(bytes)

        fun string(value: String) {
            val raw = value.toByteArray(Charsets.ISO_8859_1)
            int(raw.size)
            raw(raw)
        }

        fun binary(value: ByteArray) {
            int(value.size)
            raw(value)
        }

        fun bytes(): ByteArray = buffer.toByteArray()
    }

    private fun leInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private val CHUNK_MAGIC = "zlb\u001a".toByteArray(Charsets.US_ASCII)
    private const val RSRC_OFFSET = 0x200
    private const val INNO_OFFSET = 0x260
    private const val LOADER_TABLE_BYTES = 44
    private const val SLICE_HEADER_BYTES = 12L
}
