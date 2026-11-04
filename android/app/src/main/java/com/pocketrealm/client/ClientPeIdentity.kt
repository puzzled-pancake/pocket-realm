package com.pocketrealm.client

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Shared PE32 identity parse for WoW client executables. Extracted verbatim
 * from SafClientScanner so the folder scanner and the archive scanner agree
 * byte-for-byte on what the client version resource means. A parsed resource
 * is a client identity only when it resolves to a registered [ClientFlavor]
 * (registry gate); messages are pinned by the JVM suite.
 */
internal object ClientPeIdentity {
    data class Identity(
        val version: String?,
        val build: Int?,
        val sha256: String,
        /** [ClientFlavor.flavorId] when the version resource resolved; null otherwise. */
        val flavorId: String? = null,
        /** The version resource exactly as the exe carries it ([version]
         * is normalized to the flavor's own resource once one resolves). */
        val detectedVersion: String? = version,
    )

    /** Version-resource text the supported game versions accept. */
    internal val expectedVersions: String =
        ClientFlavors.qualified().joinToString(", ") { it.versionResource }

    /** The qualified flavor an identity resolves to, or null (import rejection). */
    internal fun qualifiedFlavor(identity: Identity?): ClientFlavor? =
        identity?.version?.let { ClientFlavors.qualifiedByVersionResource(it) }

    /** Detected-version text: the parsed resource, or why nothing was detected. */
    internal fun detected(identity: Identity?): String {
        val version = identity?.version ?: return "no version info"
        return if (identity?.flavorId == null) "$version (not a supported version)" else version
    }

    fun parse(bytes: ByteArray): Identity {
        fun u16(offset: Int): Int = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        fun u32(offset: Int): Long = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
        require(bytes.size >= 512 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) {
            "WoW.exe is not a valid Windows executable"
        }
        val pe = u32(0x3c).toInt()
        require(pe >= 0 && pe + 26 <= bytes.size && bytes.copyOfRange(pe, pe + 4).contentEquals(byteArrayOf(0x50, 0x45, 0, 0))) {
            "WoW.exe is corrupt or not a valid Windows executable"
        }
        require(u16(pe + 4) == 0x14c && u16(pe + 24) == 0x10b) {
            "WoW.exe is not a 32-bit Windows executable"
        }
        var version: String? = null
        var build: Int? = null
        var flavorId: String? = null
        var detected: String? = null
        for (offset in 0..bytes.size - 16) {
            if (bytes[offset] != 0xbd.toByte() || bytes[offset + 1] != 0x04.toByte() ||
                bytes[offset + 2] != 0xef.toByte() || bytes[offset + 3] != 0xfe.toByte()) continue
            if (u32(offset) != 0xfeef04bdL) continue
            val ms = u32(offset + 8)
            val ls = u32(offset + 12)
            val candidate = listOf((ms ushr 16).toInt(), (ms and 0xffff).toInt(), (ls ushr 16).toInt(), (ls and 0xffff).toInt())
            val flavor = ClientFlavors.qualifiedByVersionResource(candidate.joinToString("."))
            if (flavor != null) {
                version = flavor.versionResource
                build = flavor.build
                flavorId = flavor.flavorId
                detected = candidate.joinToString(".")
                break
            }
            if (version == null) {
                // Keep the first parsed resource so a rejection can name what was detected.
                version = candidate.joinToString(".")
                build = candidate[3]
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return Identity(version, build, digest, flavorId, detected ?: version)
    }
}
