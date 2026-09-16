package com.pocketrealm.importer

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset

/**
 * Synthetic client-archive fixtures shared by the JVM suite and the debug
 * instrumented suite. Everything here is synthetic: the PE stub is ported
 * verbatim from ImportFixtureProvider.syntheticPe and the MPQ stubs carry
 * only the `MPQ\x1a` header — no Blizzard bytes are ever committed.
 */
object SyntheticClientArchives {

    /** Ported verbatim from ImportFixtureProvider.syntheticPe (debug fixture). */
    fun syntheticPe(build: Int = 5875): ByteArray {
        val bytes = ByteArray(4096)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(0x3c, 0x100)
        bytes[0x100] = 0x50
        bytes[0x101] = 0x45
        buffer.putShort(0x104, 0x14c.toShort())
        buffer.putShort(0x118, 0x10b.toShort())
        buffer.putInt(0x300, 0xfeef04bd.toInt())
        buffer.putInt(0x308, (1 shl 16) or 12)
        buffer.putInt(0x30c, (1 shl 16) or build)
        return bytes
    }

    /** Stub MPQ: valid `MPQ\x1a` header plus filler so sizes differ per name. */
    fun mpqStub(name: String): ByteArray {
        val filler = ByteArray(4 + (name.length * 37) % 512) { (it % 251).toByte() }
        return byteArrayOf(0x4d, 0x50, 0x51, 0x1a) + filler
    }

    const val BUILD_5875 = 5875
    val BASE_MPQS = listOf(
        "base.MPQ", "dbc.MPQ", "fonts.MPQ", "interface.MPQ", "misc.MPQ", "model.MPQ",
        "sound.MPQ", "speech.MPQ", "terrain.MPQ", "texture.MPQ", "wmo.MPQ",
    )
    val STANDARD_ROOT_DLLS = listOf(
        "dbghelp.dll", "divxdecoder.dll", "fmod.dll", "ijl15.dll", "scan.dll", "unicows.dll",
    )

    data class Entry(
        val path: String,
        val bytes: ByteArray = ByteArray(0),
        val directory: Boolean = false,
        val unixSymlink: Boolean = false,
    )

    /** The plain vanilla-layout entries of a client, optionally under a wrapper root. */
    fun clientEntries(
        wrapper: String? = "WoW_Classic_1.12.1",
        build: Int = BUILD_5875,
        patchMpqs: List<String> = emptyList(),
        realmlist: String = "set realmlist us.logon.worldofwarcraft.com",
        configWtf: String? = null,
        extraRootFiles: List<String> = emptyList(),
        extras: List<Entry> = emptyList(),
        backslash: Boolean = false,
    ): List<Entry> {
        val sep = if (backslash) "\\" else "/"
        fun path(vararg parts: String) = (listOfNotNull(wrapper) + parts.toList()).joinToString(sep)
        return buildList {
            if (wrapper != null) add(Entry(path(), directory = true))
            add(Entry("WoW.exe".let { if (wrapper == null) it else path(it) }, syntheticPe(build)))
            STANDARD_ROOT_DLLS.forEach { dll ->
                add(Entry(if (wrapper == null) dll else path(dll), ByteArray(64) { it.toByte() }))
            }
            if (realmlist.isNotEmpty()) {
                add(Entry(if (wrapper == null) "realmlist.wtf" else path("realmlist.wtf"), realmlist.toByteArray(Charsets.US_ASCII)))
            }
            if (configWtf != null) {
                add(Entry(path("WTF"), directory = true))
                add(Entry(path("WTF", "Config.wtf"), configWtf.toByteArray(Charsets.US_ASCII)))
            }
            add(Entry(path("Data"), directory = true))
            (BASE_MPQS + patchMpqs).forEach { mpq ->
                add(Entry(path("Data", mpq), mpqStub(mpq)))
            }
            extraRootFiles.forEach { name ->
                add(Entry(path(name), "fixture-root-file\n".toByteArray(Charsets.US_ASCII)))
            }
            addAll(extras)
        }
    }

    /** The ENG-style contamination: second exe + MPQ inside a root hack folder. */
    val hackFolderEntries: List<Entry>
        get() = listOf(
            Entry("!1.8 Hack", directory = true),
            Entry("!1.8 Hack/wow.exe", syntheticPe(5875)),
            Entry("!1.8 Hack/patch-4.MPQ", mpqStub("patch-4")),
        )

    fun zip(
        target: File,
        entries: List<Entry>,
        zip64: Boolean = false,
        charset: Charset = Charsets.UTF_8,
    ): File {
        ZipArchiveOutputStream(FileOutputStream(target)).use { output ->
            output.setEncoding(charset.name())
            if (zip64) output.setUseZip64(Zip64Mode.Always)
            entries.forEach { record ->
                // Zip marks directories by a trailing slash on the raw name.
                val name = if (record.directory && !record.path.endsWith("/")) "${record.path}/" else record.path
                val entry = ZipArchiveEntry(name)
                if (!record.directory) entry.size = record.bytes.size.toLong()
                if (record.unixSymlink) {
                    entry.unixMode = 0xa1ff // S_IFLNK | 0777
                }
                output.putArchiveEntry(entry)
                if (!record.directory) output.write(record.bytes)
                output.closeArchiveEntry()
            }
            output.finish()
        }
        return target
    }

    /** COPY-method 7z so fixtures read back without org.tukaani:xz on the classpath. */
    fun sevenZip(target: File, entries: List<Entry>): File {
        SevenZOutputFile(target).use { output ->
            entries.forEach { record ->
                val entry = SevenZArchiveEntry()
                entry.name = record.path
                entry.isDirectory = record.directory
                entry.setContentMethods(listOf(SevenZMethodConfiguration(SevenZMethod.COPY)))
                if (!record.directory) entry.size = record.bytes.size.toLong()
                output.putArchiveEntry(entry)
                if (!record.directory) output.write(record.bytes)
                output.closeArchiveEntry()
            }
            output.finish()
        }
        return target
    }

    /**
     * Flip the encrypted bit (general purpose bit 0) in every local file
     * header and central directory header of a written zip, producing a
     * detection-only encrypted fixture without any crypto writer support.
     */
    fun patchEncryptionBits(target: File): File {
        val bytes = target.readBytes().also { require(it.size >= 4 && it[0] == 'P'.code.toByte()) }
        var offset = 0
        while (offset + 4 < bytes.size) {
            val signature = String(bytes, offset, 4, Charsets.US_ASCII)
            if (signature == "PK\u0003\u0004") {
                bytes[offset + 6] = (bytes[offset + 6].toInt() or 0x01).toByte()
                offset += 30
            } else if (signature == "PK\u0001\u0002") {
                bytes[offset + 8] = (bytes[offset + 8].toInt() or 0x01).toByte()
                offset += 46
            } else {
                offset += 1
            }
        }
        target.writeBytes(bytes)
        return target
    }

    /** ISO9660 sniff stub: primary volume descriptor with `CD001` at offset 0x8001. */
    fun isoStub(bytes: Int = 0x8060): ByteArray {
        val stub = ByteArray(bytes)
        val marker = "CD001".toByteArray(Charsets.US_ASCII)
        System.arraycopy(marker, 0, stub, 0x8001, marker.size)
        return stub
    }

    /** RAR4 (`Rar!` + 07 00) or RAR5 (`Rar!` + 07 01 00) signature sniff stub. */
    fun rarSignatureStub(rar5: Boolean): ByteArray {
        val header = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07)
        return if (rar5) header + byteArrayOf(0x01, 0x00) else header + byteArrayOf(0x00)
    }

    fun uniqueFile(directory: File, stem: String, suffix: String = ".zip"): File {
        directory.mkdirs()
        val file = File(directory, "$stem.$suffix")
        file.deleteOnExit()
        return file
    }
}
