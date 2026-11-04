package com.pocketrealm.server

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The SRP6 verifier-derivation half of the desktop [com.pocketrealm.desktop.AuthGate]'s
 * VsrpMath, moved to the shared sources unchanged (design-remote-accounts):
 * pure Kotlin/BigInteger/SHA-1, android-free, so the FRIEND side of remote
 * account creation derives `(s, v)` on its own device — the password never
 * leaves it — and only `{username, sHex, vHex}` crosses the provisioning
 * channel.
 *
 * The math is mirrored from cmangos (AccountMgr::CalculateShaPassHash /
 * SRP6.cpp:22-24, :96): the fixed 1.12 prime and generator, x over the
 * salt's little-endian bytes and the raw uppercased identity digest,
 * v = g^x mod N. The deterministic salt-pinned overload is the exact
 * derivation AuthGateTest golden-pins through the delegating desktop twin;
 * the random overload keeps the AuthGate salt invariant — exactly 32 bytes
 * with the top AND bottom byte nonzero — so realmd's challenge packet
 * carries the unpadded minimal salt form at exactly 32 bytes.
 *
 * SRP6 caveat the caller must respect (design-remote-accounts): (s, v)
 * is password-equivalent for offline guessing. Never log it, never persist
 * it outside the provisioning request, and discard it immediately on a
 * REJECTED verdict.
 */
object Srp6AccountMath {
    val N = BigInteger(SRP_PRIME_HEX, RADIX_HEX)
    val G = BigInteger.valueOf(SRP_GENERATOR)
    private const val SRP_PRIME_HEX =
        "894B645E89E1535BBDAD5B8B290650530801B18EBFBF5E8FAB3C82872A3E9BB7"
    private const val SRP_GENERATOR = 7L

    data class Verifier(
        val s: ByteArray,
        val sHex: String,
        val v: BigInteger,
        val vHex: String,
        val x: BigInteger,
    )

    /** Fresh random 32-byte salt with both end bytes nonzero (see class doc). */
    fun verifierFor(account: String, password: String): Verifier {
        val random = SecureRandom()
        val s = ByteArray(SALT_BYTES)
        do {
            random.nextBytes(s)
        } while (s[0] == 0.toByte() || s[SALT_BYTES - 1] == 0.toByte())
        return verifierFor(account, password, s)
    }

    /**
     * Deterministic salt-pinned verifier derivation — the golden-vector
     * path. The account/password are uppercased first (AccountMgr's
     * normalizeString); x = SHA1(s LE || identity) read little-endian;
     * v = g^x mod N.
     */
    fun verifierFor(account: String, password: String, s: ByteArray): Verifier {
        require(s.size == SALT_BYTES) { "salt must be exactly $SALT_BYTES bytes" }
        val accountUpper = account.uppercase()
        val passwordUpper = password.uppercase()
        val identity = sha1("$accountUpper:$passwordUpper".toByteArray(Charsets.US_ASCII))
        // BigNumber::SetBinary REVERSES its input (a little-endian
        // interpreter): x = the derivation digest read little-endian, over
        // the salt's LE bytes and the raw identity digest (the verifier
        // path's own std::reverse cancels the LE there).
        val x = BigInteger(1, sha1(s.reversedArray(), identity).reversedArray())
        val v = G.modPow(x, N)
        // The wire/native form is EXACTLY 64 lowercase hex chars —
        // FriendProvisionProtocol.isSrpHex and the native
        // create_account_verifier both refuse a short form — but
        // BigInteger.toString drops leading zero bytes (a top nibble of
        // zero in ~1/17 derivations), so pad to the fixed width. The
        // number is unchanged; only the encoding is fixed-width.
        return Verifier(
            s,
            s.toHexLower(),
            v,
            v.toString(RADIX_HEX).padStart(VERIFIER_HEX_CHARS, '0'),
            x,
        )
    }

    fun sha1(vararg chunks: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(chunks.fold(byteArrayOf()) { acc, b -> acc + b })

    private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

    private const val SALT_BYTES = 32
    private const val RADIX_HEX = 16

    /** The wire form's fixed width: 32 bytes as 64 lowercase hex chars. */
    private const val VERIFIER_HEX_CHARS = 64
}
