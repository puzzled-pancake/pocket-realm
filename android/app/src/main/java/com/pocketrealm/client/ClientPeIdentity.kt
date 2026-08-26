package com.pocketrealm.client

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Shared PE32 identity parse for WoW client executables. Extracted verbatim
 * from SafClientScanner so the folder scanner and the archive scanner agree
 * byte-for-byte on what "1.12.1.5875" means. Messages are pinned by the JVM
 * suite (VAL-02 wording).
 */
internal object ClientPeIdentity {
    data class Identity(val version: String?, val build: Int?, val sha256: String)

    fun parse(bytes: ByteArray): Identity {
        fun u16(offset: Int): Int = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        fun u32(offset: Int): Long = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        require(bytes.size >= 512 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) {
            "VAL-02: WoW.exe is not a PE executable"
        }
        val pe = u32(0x3c).toInt()
        require(pe >= 0 && pe + 26 <= bytes.size && bytes.copyOfRange(pe, pe + 4).contentEquals(byteArrayOf(0x50, 0x45, 0, 0))) {
            "VAL-02: WoW.exe has no valid PE header"
        }
        require(u16(pe + 4) == 0x14c && u16(pe + 24) == 0x10b) {
            "VAL-02: WoW.exe is not IMAGE_FILE_MACHINE_I386 PE32"
        }
        var version: String? = null
        var build: Int? = null
        for (offset in 0..bytes.size - 16) {
            if (bytes[offset] != 0xbd.toByte() || bytes[offset + 1] != 0x04.toByte() ||
                bytes[offset + 2] != 0xef.toByte() || bytes[offset + 3] != 0xfe.toByte()) continue
            if (u32(offset) != 0xfeef04bdL) continue
            val ms = u32(offset + 8)
            val ls = u32(offset + 12)
            val candidate = listOf((ms ushr 16).toInt(), (ms and 0xffff).toInt(), (ls ushr 16).toInt(), (ls and 0xffff).toInt())
            if (candidate[0] == 1 && candidate[1] == 12 && candidate[2] == 1) {
                version = candidate.joinToString(".")
                build = candidate[3]
                break
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return Identity(version, build, digest)
    }
}
