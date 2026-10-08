package com.pocketrealm.supervisor

import com.pocketrealm.client.ClientFlavors
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM coverage for the pure parts of [UserAccountStore]: the realm account
 * validation rule and [UserAccountStore.UserAccount] secret redaction. The
 * file-I/O path (Context + Os, atomic write, perms) is covered by the
 * instrumented `UserAccountStoreInstrumentedTest` in androidTest.
 *
 * The G3-1 flavor-shim cases drive the pure record codec
 * ([UserAccountStore.decodeRecord] / [UserAccountStore.encodeRecord]) against
 * the committed current-release fixture: a record that predates the flavor key
 * decodes as vanilla and is never quarantined for its absence, a known
 * non-vanilla flavor decodes, an unknown flavor value throws (the throw is
 * what drives `loadOrQuarantine()`'s rename), and a vanilla save re-encodes to
 * the exact committed bytes (byte-stability of the vanilla record).
 */
class UserAccountStoreValidationTest {
    private fun fixtureText(name: String): String =
        javaClass.getResourceAsStream("/fixtures/g3/$name")!!.readBytes().toString(Charsets.UTF_8)

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

    @Test fun `current-release fixture record without a flavor key loads as vanilla`() {
        val account = UserAccountStore.decodeRecord(
            JSONObject(fixtureText("user-account-current-release.json")),
        )
        assertEquals("player1", account.username)
        assertEquals("hunter2", account.password)
        assertEquals(42L, account.accountId)
        assertEquals(0, account.gmLevel)
        assertEquals(ClientFlavors.VANILLA.flavorId, account.flavor)
    }

    @Test fun `record with a known non-vanilla flavor loads`() {
        val value = JSONObject(fixtureText("user-account-current-release.json"))
        value.put("flavor", ClientFlavors.TBC.flavorId)
        val account = UserAccountStore.decodeRecord(value)
        assertEquals(ClientFlavors.TBC.flavorId, account.flavor)
    }

    @Test fun `record with an unknown flavor value is refused so loadOrQuarantine relocates it`() {
        val value = JSONObject(fixtureText("user-account-current-release.json"))
        value.put("flavor", "cata")
        try {
            UserAccountStore.decodeRecord(value)
            throw AssertionError("unknown flavor must not decode")
        } catch (expected: IllegalArgumentException) {
            assertEquals("invalid stored user-account flavor", expected.message)
        }
    }

    @Test fun `vanilla save re-encodes to the committed current-release content`() {
        val fixture = javaClass.getResourceAsStream("/fixtures/g3/user-account-current-release.json")!!
            .readBytes()
        val encoded = UserAccountStore.encodeRecord(
            UserAccountStore.UserAccount("player1", "hunter2", 42L, 0),
        )
        // The committed fixture is the SHIPPING (Android org.json) byte shape:
        // insertion-ordered `{"schema":2,"username":…,"password":…,
        // "accountId":…,"gmLevel":…}`. The JVM test classpath's org.json
        // artifact hash-scrambles key order, so this lane pins the CONTENT —
        // exact key set (no flavor key), values, and types. The raw
        // deployed-byte equality is pinned on the device, where the platform
        // org.json runs (UserAccountStoreInstrumentedTest).
        assertArrayEquals(canonicalJson(fixture), canonicalJson(encoded))
    }

    /** Order-insensitive, content-exact serialization (sorted keys, compact). */
    private fun canonicalJson(bytes: ByteArray): ByteArray {
        val value = JSONObject(String(bytes, Charsets.UTF_8))
        return canonical(value).toByteArray(Charsets.UTF_8)
    }

    private fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonical(value.get(key))
        }
        is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { index ->
            canonical(value.get(index))
        }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }

    @Test fun `non-vanilla save carries the flavor key and round-trips`() {
        val account = UserAccountStore.UserAccount(
            "player1", "hunter2", 42L, 0, ClientFlavors.TBC.flavorId,
        )
        val decoded = UserAccountStore.decodeRecord(
            JSONObject(String(UserAccountStore.encodeRecord(account), Charsets.UTF_8)),
        )
        assertEquals(ClientFlavors.TBC.flavorId, decoded.flavor)
        assertEquals("player1", decoded.username)
        assertEquals(42L, decoded.accountId)
    }
}
