package com.pocketrealm.importer.inno

import java.io.InputStream

/**
 * Reverses the x86 CALL/JMP address transform Inno applies to stored
 * executables for better compression. Two generations exist: the pre-5.2.0
 * accumulator variant (whose address seed starts at five) and the 5.2.0+
 * block-aware variant with the optional high-byte flip added for 5.3.9.
 * The transform runs per file over decompressed chunk bytes; every other
 * byte passes through untouched.
 */
class InnoCallFilterInputStream(
    private val source: InputStream,
    variant: Variant,
) : InputStream() {

    enum class Variant { PRE_5_2_0, SINCE_5_2_0, SINCE_5_3_9 }

    private val blockAware = variant != Variant.PRE_5_2_0
    private val flipHighByte = variant == Variant.SINCE_5_3_9

    // Shared output queue: transformed address bytes wait here.
    private val pending = ByteArray(4)
    private var pendingFrom = 0
    private var pendingTo = 0

    // PRE_5_2_0 state.
    private var legacyAddress = 0
    private var legacyBytesLeft = 0
    private var legacyOffset = 5

    // Block-aware state.
    private var collecting = 0 // negative while gathering the four address bytes
    private var offset = 0L

    override fun read(): Int =
        if (blockAware) pullBlockAware() else pullLegacy()

    override fun read(dest: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var done = 0
        while (done < len) {
            val byte = if (blockAware) pullBlockAware() else pullLegacy()
            if (byte < 0) break
            dest[off + done] = byte.toByte()
            done++
        }
        return if (done == 0) -1 else done
    }

    private fun pullLegacy(): Int {
        val byte = source.read()
        if (byte < 0) return -1
        var out = byte
        if (legacyBytesLeft == 0) {
            if (byte == 0xe8 || byte == 0xe9) {
                legacyAddress = -legacyOffset
                legacyBytesLeft = 4
            }
        } else {
            legacyAddress += byte and 0xff
            out = legacyAddress and 0xff
            legacyAddress = legacyAddress ushr 8
            legacyBytesLeft--
        }
        legacyOffset++
        return out
    }

    private fun pullBlockAware(): Int {
        if (pendingFrom < pendingTo) return pending[pendingFrom++].toInt() and 0xff
        pendingFrom = 0
        pendingTo = 0
        if (collecting == 0) {
            val byte = source.read()
            if (byte < 0) return -1
            offset++
            if (byte != 0xe8 && byte != 0xe9) return byte
            val leftInBlock = (BLOCK_BYTES - ((offset - 1) % BLOCK_BYTES)).toInt()
            if (leftInBlock < 5) return byte // instruction spans a block edge
            collecting = -4
            // The opcode itself passes through unmodified; deliver it before
            // the (possibly rewritten) address bytes.
            return byte
        }
        while (collecting < 0) {
            val byte = source.read()
            if (byte < 0) {
                // EOF inside an address: deliver the bytes we did collect,
                // then EOF — leaving `collecting` negative would re-enter this
                // loop and re-deliver them forever.
                pendingTo = 4 + collecting
                collecting = 0
                return if (pendingFrom < pendingTo) {
                    pending[pendingFrom++].toInt() and 0xff
                } else {
                    -1
                }
            }
            pending[4 + collecting] = byte.toByte()
            collecting++
            offset++
        }
        if ((pending[3].toInt() and 0xff) == 0x00 || (pending[3].toInt() and 0xff) == 0xff) {
            val address = (offset and 0xffffff).toInt()
            var rel = (pending[0].toInt() and 0xff) or
                ((pending[1].toInt() and 0xff) shl 8) or
                ((pending[2].toInt() and 0xff) shl 16)
            rel -= address
            pending[0] = rel.toByte()
            pending[1] = (rel shr 8).toByte()
            pending[2] = (rel shr 16).toByte()
            if (flipHighByte && (rel and 0x800000) != 0) {
                pending[3] = (pending[3].toInt() xor 0xff).toByte()
            }
        }
        pendingFrom = 0
        pendingTo = 4
        return pending[pendingFrom++].toInt() and 0xff
    }

    private companion object {
        const val BLOCK_BYTES = 0x10000L
    }
}
