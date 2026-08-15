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
}
