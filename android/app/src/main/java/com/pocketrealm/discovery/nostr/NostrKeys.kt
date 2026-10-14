package com.pocketrealm.discovery.nostr

import fr.acinq.secp256k1.Secp256k1
import java.security.SecureRandom

/**
 * W6-N1 (docs/NOSTR-IROH-BUILD-PLAN.md stage 1): the BIP-340 key seam for
 * the Nostr discovery lane — the single place the realm's listing identity
 * is generated, parsed, and used to sign/verify.
 *
 * Signatures ride the Acinq secp256k1-kmp library (the wave's one real
 * dependency): Schnorr per BIP-340, x-only 32-byte public keys, exactly
 * what NIP-01 requires. Verification is done OURSELVES (the adopted
 * consult ruling) — never trusted to the relay.
 *
 * The private key is the realm's identity, not a user account (NIP-42 is
 * "a key, not a signup"). Storage is the host's own settings store; this
 * class never persists anything.
 */
class NostrPrivateKey private constructor(
    /** Exactly 32 bytes; a validated BIP-340 secret key. */
    val bytes: ByteArray,
) {

    /** The 32-byte x-only public key (BIP-340), hex-encoded lowercase —
     * the `pubkey` field of every event this key signs. */
    val xOnlyPublicKeyHex: String by lazy {
        val compressed = Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(bytes))
        Hex.encode(compressed.copyOfRange(Hex.X_ONLY_START, Hex.X_ONLY_END))
    }

    /**
     * BIP-340 Schnorr-sign [message32] (for Nostr: the event id bytes —
     * already a SHA-256 digest). [auxRand32] is the deterministic-nonce
     * randomness; production callers pass null (the library draws fresh
     * randomness) and the golden-vector tests pass the official fixed
     * aux bytes. Returns the 64-byte compact signature.
     */
    fun signSchnorr(message32: ByteArray, auxRand32: ByteArray? = null): ByteArray {
        require(message32.size == KEY_BYTES) { "BIP-340 signs exactly $KEY_BYTES-byte messages" }
        return Secp256k1.signSchnorr(message32, bytes, auxRand32)
    }

    override fun equals(other: Any?): Boolean = other is NostrPrivateKey && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    companion object {
        /** Generates a fresh, validated secret key (a 1-in-2^k invalid draw
         * is re-drawn, never surfaced). */
        fun generate(random: SecureRandom = SecureRandom()): NostrPrivateKey {
            while (true) {
                val candidate = ByteArray(KEY_BYTES).also(random::nextBytes)
                val key = fromBytesOrNull(candidate) ?: continue
                return key
            }
        }

        /** Parses a 32-byte hex secret key; fails loud (VAL-NOSTR-KEY) on
         * anything that is not a valid BIP-340 secret key. */
        fun fromHex(hex: String): NostrPrivateKey =
            fromBytesOrNull(Hex.decode(hex, "VAL-NOSTR-KEY"))
                ?: throw IllegalArgumentException(
                    "VAL-NOSTR-KEY: not a valid BIP-340 secret key (out of range or zero)",
                )

        fun fromBytes(bytes: ByteArray): NostrPrivateKey =
            fromBytesOrNull(bytes)
                ?: throw IllegalArgumentException(
                    "VAL-NOSTR-KEY: not a valid BIP-340 secret key (must be 32 bytes, in range, non-zero)",
                )

        private fun fromBytesOrNull(bytes: ByteArray): NostrPrivateKey? = try {
            val valid = bytes.size == KEY_BYTES && Secp256k1.secKeyVerify(bytes)
            if (valid) NostrPrivateKey(bytes.copyOf()) else null
        } catch (_: fr.acinq.secp256k1.Secp256k1Exception) {
            null
        }

        private const val KEY_BYTES = 32
    }
}

/**
 * Lowercase hex codec for the Nostr wire (ids, pubkeys, signatures are all
 * lowercase hex per NIP-01). Decoding fails loud with the caller's VAL code.
 */
object Hex {

    private val DIGITS = "0123456789abcdef".toCharArray()
    private const val HEX_RADIX = 16
    private const val HEX_CHARS_PER_BYTE = 2
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F
    private const val BYTE_MASK = 0xFF

    /** The x-only key sits at bytes 1..32 of a compressed point. */
    internal const val X_ONLY_START = 1
    internal const val X_ONLY_END = 33

    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * HEX_CHARS_PER_BYTE)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and BYTE_MASK
            out[i * 2] = DIGITS[v ushr NIBBLE_BITS]
            out[i * 2 + 1] = DIGITS[v and NIBBLE_MASK]
        }
        return String(out)
    }

    fun decode(hex: String, valCode: String): ByteArray {
        val clean = hex.trim().lowercase()
        require(clean.length % HEX_CHARS_PER_BYTE == 0 && clean.all { it in DIGITS }) {
            "$valCode: expected even-length lowercase hex"
        }
        return ByteArray(clean.length / HEX_CHARS_PER_BYTE) { i ->
            val high = Character.digit(clean[i * 2], HEX_RADIX)
            val low = Character.digit(clean[i * 2 + 1], HEX_RADIX)
            ((high shl NIBBLE_BITS) or low).toByte()
        }
    }
}
