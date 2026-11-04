package com.pocketrealm.server

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared SRP6 verifier math (design-remote-accounts): the same
 * reference-derivation golden vector the desktop AuthGateTest pins through
 * the delegating VsrpMath twin, re-pinned directly on
 * [Srp6AccountMath] in the shared lane. The fixture was derived from
 * native/cmangos/src/shared/Auth/SRP6.cpp + AccountMgr::CalculateShaPassHash
 * (a Python re-derivation written from the core source), not from the
 * Kotlin under test.
 */
class Srp6AccountMathTest {
    @Test
    fun verifierGoldenVectorMatchesTheReferenceDerivation() {
        val verifier = Srp6AccountMath.verifierFor(
            GOLDEN_ACCOUNT,
            GOLDEN_PASSWORD,
            bytes(GOLDEN_SALT_HEX),
        )
        assertEquals(
            "x = the derivation digest read little-endian",
            BigInteger(GOLDEN_X_HEX, 16),
            verifier.x,
        )
        assertEquals(
            "v = g^x mod N — the value the account row's v column carries",
            BigInteger(GOLDEN_V_HEX, 16),
            verifier.v,
        )
        assertEquals(
            "sHex is the salt's lowercase hex form",
            GOLDEN_SALT_HEX.lowercase(),
            verifier.sHex,
        )
        assertTrue("vHex is lowercase", verifier.vHex.all { it.isDigit() || it in 'a'..'f' })
        assertEquals(
            "vHex is the fixed-width wire form (64 lowercase hex chars)",
            64,
            verifier.vHex.length,
        )
    }

    /** The random lane keeps the unpadded-salt invariant: exactly 32 bytes
     * with both end bytes nonzero, so realmd's challenge packet carries the
     * minimal salt form at exactly 32 bytes with no length prefix. */
    @Test
    fun randomVerifierKeepsTheThirtyTwoByteSaltInvariant() {
        repeat(SALT_INVARIANT_ROUNDS) {
            val verifier = Srp6AccountMath.verifierFor(GOLDEN_ACCOUNT, GOLDEN_PASSWORD)
            assertEquals(32, verifier.s.size)
            assertTrue(verifier.s[0] != 0.toByte())
            assertTrue(verifier.s[31] != 0.toByte())
            assertEquals(64, verifier.sHex.length)
        }
    }

    /** Fresh random salts per derivation — two calls never share a salt
     * (the invite payload's guess resistance starts here). */
    @Test
    fun randomSaltsDifferBetweenDerivations() {
        val first = Srp6AccountMath.verifierFor(GOLDEN_ACCOUNT, GOLDEN_PASSWORD)
        val second = Srp6AccountMath.verifierFor(GOLDEN_ACCOUNT, GOLDEN_PASSWORD)
        assertNotEquals(first.sHex, second.sHex)
        assertNotEquals(first.vHex, second.vHex)
    }

    /** The identity is uppercased before hashing (AccountMgr's
     * normalizeString): a mixed-case spelling derives the SAME verifier as
     * its uppercase form — realmd will authenticate either spelling. */
    @Test
    fun identityIsNormalizedLikeTheCore() {
        val upper = Srp6AccountMath.verifierFor("DAVE", "Sunshine-River-7", bytes(GOLDEN_SALT_HEX))
        val mixed = Srp6AccountMath.verifierFor("dave", "sunshine-river-7", bytes(GOLDEN_SALT_HEX))
        assertEquals(upper.x, mixed.x)
        assertEquals(upper.vHex, mixed.vHex)
    }

    private fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "non-hex golden fixture" }
            ((high shl 4) or low).toByte()
        }

    private companion object {
        const val GOLDEN_ACCOUNT = "AUTHGATE"
        const val GOLDEN_PASSWORD = "AuthGate-Password-1"
        const val GOLDEN_SALT_HEX =
            "3B7C8FA1D2E4F6051728394A5B6C7D8E9F0A1B2C3D4E5F60718293A4B5C6D7E8"
        const val GOLDEN_X_HEX = "71286F5F2DA3FE3596545DED97840B4729AD311E"
        const val GOLDEN_V_HEX =
            "5DC9156C55061873998E3AAE7937B70E5AD809A6A7EC5C1667AFC7BF153870FD"
        const val SALT_INVARIANT_ROUNDS = 16
    }
}
