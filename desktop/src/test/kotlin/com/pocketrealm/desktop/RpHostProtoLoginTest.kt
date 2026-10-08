package com.pocketrealm.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The proto-login op's guarded-interpolation pins: the stdin `user`
 * field must pass the engine's account-token gate before any SQL
 * exists, and the realmd UPDATE must single-quote double the username
 * (the DesktopSqliteConnection JNI seam interpolates with no bind
 * parameters — the DesktopGuestBans escape idiom). A quoted payload
 * (`x' OR '1'='1`) must never reach the statement as live SQL.
 */
class RpHostProtoLoginTest {
    /** A deterministic verifier: fixed-width lowercase hex v/s. */
    private fun verifier(user: String) = VsrpMath.verifierFor(user, "pass", ByteArray(32))

    @Test
    fun userIsUppercasedAndAdmittedWhenTokenShaped() {
        assertEquals("RPUSER", protoLoginUser("rpuser"))
    }

    @Test
    fun injectionShapedUsersRefuseBeforeAnySqlExists() {
        listOf(
            "x' OR '1'='1",
            "a'; DROP TABLE account; --",
            "v='deadbeef' WHERE '1'='1",
            "has space",
            "mindé",
            "", // and the two length edges
            "0123456789abcdefg", // 17 chars
        ).forEach { raw ->
            val refused = runCatching { protoLoginUser(raw) }.isFailure
            assertTrue("the token gate must refuse '${raw.replace("\n", "\\n")}': $refused", refused)
        }
    }

    @Test
    fun accountUpdateRendersTheGuardedShape() {
        val sql = protoLoginAccountUpdate("RPUSER", verifier("RPUSER"))
        assertTrue(
            "v/s interpolate as fixed-width hex, the username quoted: $sql",
            Regex("^UPDATE account SET v = '[0-9a-f]{64}', s = '[0-9a-f]{64}' WHERE username = 'RPUSER';$")
                .matches(sql),
        )
    }

    @Test
    fun accountUpdateDoublesSingleQuotesSoAPayloadCannotBreakOut() {
        // Defense in depth past the gate: even a quote-bearing value
        // stays inside the string literal — every ' the caller supplies
        // arrives as ''.
        val sql = protoLoginAccountUpdate("x' OR '1'='1", verifier("RPUSER"))
        assertEquals(
            "UPDATE account SET v = '${verifier("RPUSER").vHex}', s = '${verifier("RPUSER").sHex}' " +
                "WHERE username = 'x'' OR ''1''=''1';",
            sql,
        )
    }
}
