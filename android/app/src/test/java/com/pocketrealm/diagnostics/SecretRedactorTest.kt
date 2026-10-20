package com.pocketrealm.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretRedactorTest {
    @Test fun structuredAndRegexCanariesAreRemovedWithoutLosingLoopback() {
        val canaries = listOf("PaSsCanary42", "DbSecret77", "SOURCEFOLDER")
        val input = """{
          "password":"PaSsCanary42",
          "dbCredential":"DbSecret77",
          "message":"sourcefolder content://fixture/tree C:\\Users\\Alice\\WoW /data/user/0/app/file",
          "account":"Player@example.com",
          "remote":"192.168.1.44",
          "local":"127.0.0.1"
        }""".trimIndent()
        val output = SecretRedactor(canaries).redact(input)
        canaries.forEach { assertFalse(output.contains(it, ignoreCase = true)) }
        assertFalse(output.contains("content://"))
        assertFalse(output.contains("C:\\Users"))
        assertFalse(output.contains("/data/user"))
        assertFalse(output.contains("192.168.1.44"))
        assertTrue(output.contains("127.0.0.1"))
        assertTrue(output.contains("<redacted>"))
    }

    @Test fun tokenFieldsAreRedactedButStableRuntimeHashesRemain() {
        val hash = "a".repeat(64)
        val output = SecretRedactor().redact(
            """{"instanceToken":"${"b".repeat(64)}","runtimeSha256":"$hash"}""")
        assertFalse(output.contains("b".repeat(64)))
        assertTrue(output.contains(hash))
    }

    /** The realm's Nostr identity key is
     * a signing secret — its key name matches SENSITIVE_KEY and the value
     * is scrubbed from any structured body. */
    @Test fun nostrPrivateKeyHexIsRedactedFromStructuredBodies() {
        val canary = "4f5e6a7b8c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f708192a3b4c5d6e7f8091"
        val body = "{\"nostrDiscoveryEnabled\":true,\"nostrPrivateKeyHex\":\"$canary\"}"
        val output = SecretRedactor(listOf(canary)).redact(body)
        assertFalse(output.contains(canary))
        assertTrue(output.contains("nostrPrivateKeyHex"))
        assertTrue(output.contains("<redacted>"))
    }
}
