package com.pocketrealm.desktop

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.server.ServerRuntimeFiles
import java.io.File
import java.math.BigInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-client-build SRP6 auth fixtures (DESK-56): the auth math and wire
 * identity golden vectors plus the frame-size pins derived from the
 * shared [ClientFlavors] registry's srp6 fields.
 *
 * Golden-vector provenance: the pinned values were derived with an
 * INDEPENDENT implementation of the reference algorithm
 * (native/cmangos/src/shared/Auth/SRP6.cpp — CalculateVerifier,
 * HashSessionKey, CalculateProof, Finalize — plus
 * AccountMgr::CalculateShaPassHash for the identity), a Python
 * re-derivation written from that source, not from the Kotlin under
 * test. Agreement here is therefore a cross-check of [VsrpMath]'s
 * BigInteger endianness discipline (AsByteArray's little-endian forms,
 * SetBinary's reversal), which a self-consistent reimplementation could
 * never provide. The live-socket half of the gate remains gradlew
 * authGate; this suite pins everything that is pure math or pure wire
 * layout.
 */
class AuthGateTest {
    @Test
    fun verifierGoldenVectorMatchesTheReferenceDerivation() {
        val verifier = VsrpMath.verifierFor(GOLDEN_ACCOUNT, GOLDEN_PASSWORD, bytes(GOLDEN_SALT_HEX))
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
    }

    /** The random lane keeps the gate's unpadded-salt invariant: exactly
     * 32 bytes with both end bytes nonzero, so the challenge packet's s
     * field is exactly 32 bytes with no length prefix. */
    @Test
    fun randomVerifierKeepsTheThirtyTwoByteSaltInvariant() {
        repeat(SALT_INVARIANT_ROUNDS) {
            val verifier = VsrpMath.verifierFor(GOLDEN_ACCOUNT, GOLDEN_PASSWORD)
            assertEquals(32, verifier.s.size)
            assertTrue(verifier.s[0] != 0.toByte())
            assertTrue(verifier.s[31] != 0.toByte())
        }
    }

    @Test
    fun sessionKeyGoldenVectorMatchesTheReferenceInterleave() {
        assertEquals(
            "K = the 40-byte even/odd SHA-1 interleave of S's little-endian form",
            BigInteger(GOLDEN_K_HEX, 16),
            VsrpMath.sessionKey(BigInteger(GOLDEN_S_HEX, 16)),
        )
    }

    @Test
    fun clientProofGoldenVectorMatchesTheReferenceCalculation() {
        val m1 = VsrpMath.clientProof(
            GOLDEN_ACCOUNT,
            BigInteger(GOLDEN_S_HEX, 16),
            BigInteger(GOLDEN_A_HEX, 16),
            BigInteger(GOLDEN_B_HEX, 16),
            BigInteger(GOLDEN_K_HEX, 16),
        )
        assertTrue(
            "M1 = SHA1(N^g || user || s || A || B || K): ${m1.toHexUpper()}",
            bytes(GOLDEN_M1_HEX).contentEquals(m1),
        )
    }

    @Test
    fun serverProofGoldenVectorMatchesTheReferenceFinalize() {
        val m2 = VsrpMath.serverProof(
            BigInteger(GOLDEN_A_HEX, 16),
            bytes(GOLDEN_M1_HEX),
            BigInteger(GOLDEN_K_HEX, 16),
        )
        assertTrue(
            "M2 = SHA1(A || M1 || K): ${m2.toHexUpper()}",
            bytes(GOLDEN_M2_HEX).contentEquals(m2),
        )
    }

    /** DESK-56 frame pins, derived from the ClientFlavor srp6 fields:
     * the shared challenge opcode is ONE constant for all branches, the
     * vanilla frame length is registry data, the client's parsed layout
     * equals it, and the tbc/wotlk lengths are the S8-verified
     * sAuthLogonProof_S size (cmd + error + M2[20] + uint32 accountFlags +
     * uint32 surveyId + uint16 unkFlags = 32, AuthSocket.cpp:135-143 in both
     * branches — lanes item 14). The vanilla logon client still refuses to
     * parse anything but its own 26-byte layout until the per-flavor parser
     * lands with those lanes. */
    @Test
    fun frameSizePinsResolveFromTheClientFlavorSrp6Fields() {
        assertEquals(0x1EC, ClientFlavors.AUTH_CHALLENGE_OPCODE)
        assertEquals(
            "vanilla: cmd + error + M2[20] + uint32 LoginFlags",
            VsrpClient.VANILLA_PROOF_RESPONSE_BYTES,
            ClientFlavors.VANILLA.proofResponseBytes,
        )
        assertEquals(
            "tbc: cmd + error + M2[20] + uint32 accountFlags + uint32 surveyId + uint16 unkFlags",
            32,
            ClientFlavors.TBC.proofResponseBytes,
        )
        assertEquals(
            "wotlk: the same sAuthLogonProof_S layout as tbc",
            32,
            ClientFlavors.WOTLK.proofResponseBytes,
        )
        assertEquals(
            "the lane the gates resolve pins exactly the vanilla frame",
            ClientFlavors.VANILLA,
            ServerRuntimeFiles.serverFlavor(),
        )
    }

    /** The exact sAuthLogonChallengeBody for the vanilla identity —
     * gamename[4] "WoW\0", versionResource's major.minor.patch, build
     * LE u16, platform/os/locale REVERSED (os/platform NUL-terminated
     * to 4 bytes, terminator LAST so the server's pre-un-reverse
     * C-string read parses them), timezone bias, ip, name (DESK-94: the
     * identity fields ride the registry, so this golden pins the vanilla
     * pairing of registry data and layout). */
    @Test
    fun challengeBodyGoldenBytesPinTheVanillaIdentity() {
        val body = VsrpClient.challengeBody(GOLDEN_ACCOUNT, ClientFlavors.VANILLA)
        assertTrue(
            "challenge body must be the pinned vanilla wire bytes: ${body.toHexUpper()}",
            bytes(GOLDEN_CHALLENGE_BODY_HEX).contentEquals(body),
        )
        assertEquals(CHALLENGE_BODY_BYTES, body.size)
    }

    /** DESK-94 unit pin: the resolved client build equals the runtime
     * lockfile's cmangos lane. schemas/realm-runtime-lockfile-sqlite-win
     * IS the vanilla lane's lockfile (only vanilla exists — see
     * tests/test_win_lockfile.py); its cmangos_commit is the lane the
     * desktop BuildConfig pins, and the auth client resolves exactly
     * that lane's client build from the registry — no free-floating
     * build constant survives in the auth path. */
    @Test
    fun resolvedBuildEqualsTheLockfilesCmangosVanillaLane() {
        val lockfile = File("../schemas/realm-runtime-lockfile-sqlite-win.json")
        assertTrue(
            "the win sqlite lockfile must exist: ${lockfile.absolutePath}",
            lockfile.isFile,
        )
        val record = JSONObject(lockfile.readText())
        assertEquals("sqlite", record.getString("database_backend"))
        assertEquals(
            "the lockfile's lane is the lane the desktop build pins",
            com.pocketrealm.BuildConfig.NATIVE_CMANGOS_COMMIT,
            record.getString("cmangos_commit"),
        )
        val flavor = ServerRuntimeFiles.serverFlavor()
        assertEquals(ClientFlavors.VANILLA, flavor)
        assertEquals(5875, flavor.build)
        assertEquals("1.12.1.5875", flavor.versionResource)
    }

    // ---- golden fixtures --------------------------------------------

    private fun bytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            val high = Character.digit(hex[i * 2], 16)
            val low = Character.digit(hex[i * 2 + 1], 16)
            require(high >= 0 && low >= 0) { "non-hex golden fixture" }
            ((high shl 4) or low).toByte()
        }

    private fun ByteArray.toHexUpper(): String =
        joinToString("") { "%02X".format(it) }

    private companion object {
        /** The authGate gate's own fixture account. */
        const val GOLDEN_ACCOUNT = "AUTHGATE"
        const val GOLDEN_PASSWORD = "AuthGate-Password-1"

        /** Fixed 32-byte salt (both end bytes nonzero — the unpadded-form
         * invariant above). */
        const val GOLDEN_SALT_HEX =
            "3B7C8FA1D2E4F6051728394A5B6C7D8E9F0A1B2C3D4E5F60718293A4B5C6D7E8"

        /** x = LE(SHA1(sLE || SHA1("AUTHGATE:AUTHGATE-PASSWORD-1"))). */
        const val GOLDEN_X_HEX = "71286F5F2DA3FE3596545DED97840B4729AD311E"
        const val GOLDEN_V_HEX =
            "5DC9156C55061873998E3AAE7937B70E5AD809A6A7EC5C1667AFC7BF153870FD"

        /** Fixed 32-byte SRP session secret S. */
        const val GOLDEN_S_HEX =
            "11223344556677889A0B0C0D0E0F101112131415161718191A1B1C1D1E1F2021"
        const val GOLDEN_K_HEX =
            "63ba9b273782cea6795228e84384d2de9c47e16c6f54ed93b80cc966322ed09d349dcb8785ee6468"

        /** Fixed client/server ephemeral publics A and B. */
        const val GOLDEN_A_HEX =
            "A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0"
        const val GOLDEN_B_HEX =
            "B0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0C1C2C3C4C5C6C7C8C9CACBCCCDCECF"

        const val GOLDEN_M1_HEX = "72443971FA2F616FB68DEDA8F16A82E5092348CE"
        const val GOLDEN_M2_HEX = "7B896D61AB3AC88899EA23AF2E1A2087F992E84B"

        /** The vanilla challenge body for GOLDEN_ACCOUNT (38 bytes). */
        const val GOLDEN_CHALLENGE_BODY_HEX =
            "576F5700010C01F316363878006E69570053556E65000000007F000001" +
                "084155544847415445"
        const val CHALLENGE_BODY_BYTES = 38

        const val SALT_INVARIANT_ROUNDS = 16
    }
}
