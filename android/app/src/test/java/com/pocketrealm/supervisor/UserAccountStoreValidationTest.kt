package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM coverage for the pure parts of [UserAccountStore]: the realm account
 * validation rule and [UserAccountStore.UserAccount] secret redaction. The
 * file-I/O path (Context + Os, atomic write, perms) is covered by the
 * instrumented `UserAccountStoreInstrumentedTest` in androidTest.
 */
class UserAccountStoreValidationTest {
    @Test fun `valid credentials pass the realm rule`() {
        assertTrue(UserAccountStore.isValidCredential("player1"))
        assertTrue(UserAccountStore.isValidCredential("Abc123"))
        assertTrue(UserAccountStore.isValidCredential("x"))       // length 1
        assertTrue(UserAccountStore.isValidCredential("a".repeat(16))) // length 16
    }

    @Test fun `invalid credentials are rejected`() {
        assertFalse(UserAccountStore.isValidCredential(""))        // empty
        assertFalse(UserAccountStore.isValidCredential("a".repeat(17))) // too long
        assertFalse(UserAccountStore.isValidCredential("bad-name")) // hyphen
        assertFalse(UserAccountStore.isValidCredential("space name")) // space
        assertFalse(UserAccountStore.isValidCredential("ünïcödé"))   // non-ascii
    }

    @Test fun `toString never reveals the secret`() {
        val account = UserAccountStore.UserAccount("player1", "topsecret", 42L)
        val rendered = account.toString()
        assertTrue("username leaked", !rendered.contains("topsecret"))
        assertTrue("password leaked", !rendered.contains("player1"))
        assertTrue(rendered.contains("accountId=42"))
        assertEquals("player1", account.username)
        assertEquals("topsecret", account.password)
        assertEquals(42L, account.accountId)
    }
}
