package com.pocketrealm.client

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM unit tests for [ImeCharMap], the O14 increment-2 IME committed-text
 * policy. These verify the bounded supported character set, shift handling,
 * punctuation mapping, unsupported-character rejection, commit length limits,
 * and the test fixture phrase.
 *
 * Uses Android's stub returns (`isReturnDefaultValues = true`), so `KeyEvent`
 * constructors succeed on the host JVM with default field values. The tests
 * assert on [ImeCharMap.Mapping] fields (keyCode, shift), not on KeyEvent
 * internals.
 */
class ImeCharMapTest {

    @Test fun `lowercase a-z all supported and unshifted`() {
        for (c in 'a'..'z') {
            assertTrue("$c should be supported", ImeCharMap.isSupported(c))
            val r = ImeCharMap.map(c.toString())
            assertTrue(r.allAccepted)
            assertEquals(1, r.accepted.size)
            assertEquals(KeyEvent.KEYCODE_A + (c - 'a'), r.accepted[0].keyCode)
            assertFalse("lowercase should not need shift", r.accepted[0].shift)
        }
    }

    @Test fun `uppercase A-Z all supported and shifted`() {
        for (c in 'A'..'Z') {
            assertTrue("$c should be supported", ImeCharMap.isSupported(c))
            val r = ImeCharMap.map(c.toString())
            assertTrue(r.allAccepted)
            assertEquals(KeyEvent.KEYCODE_A + (c - 'A'), r.accepted[0].keyCode)
            assertTrue("uppercase should need shift", r.accepted[0].shift)
        }
    }

    @Test fun `digits 0-9 all supported and unshifted`() {
        for (c in '0'..'9') {
            assertTrue(ImeCharMap.isSupported(c))
            val r = ImeCharMap.map(c.toString())
            assertTrue(r.allAccepted)
            assertEquals(KeyEvent.KEYCODE_0 + (c - '0'), r.accepted[0].keyCode)
            assertFalse(r.accepted[0].shift)
        }
    }

    @Test fun `space is supported`() {
        val r = ImeCharMap.map(" ")
        assertTrue(r.allAccepted)
        assertEquals(KeyEvent.KEYCODE_SPACE, r.accepted[0].keyCode)
    }

    @Test fun `common chat punctuation all supported`() {
        val punctuation = setOf('.', ',', '!', '?', '\'', '"', ':', ';', '-', '_', '/', '\\', '(', ')', '[', ']')
        for (c in punctuation) {
            assertTrue("$c should be supported for chat", ImeCharMap.isSupported(c))
        }
        val r = ImeCharMap.map(punctuation.joinToString(""))
        assertTrue("all chat punctuation should be accepted: ${r.rejected}", r.allAccepted)
    }

    @Test fun `exclamation mark is shifted 1`() {
        val r = ImeCharMap.map("!")
        assertTrue(r.allAccepted)
        assertEquals(KeyEvent.KEYCODE_1, r.accepted[0].keyCode)
        assertTrue(r.accepted[0].shift)
    }

    @Test fun `question mark is shifted slash`() {
        val r = ImeCharMap.map("?")
        assertTrue(r.allAccepted)
        assertEquals(KeyEvent.KEYCODE_SLASH, r.accepted[0].keyCode)
        assertTrue(r.accepted[0].shift)
    }

    @Test fun `underscore is shifted minus`() {
        val r = ImeCharMap.map("_")
        assertTrue(r.allAccepted)
        assertEquals(KeyEvent.KEYCODE_MINUS, r.accepted[0].keyCode)
        assertTrue(r.accepted[0].shift)
    }

    @Test fun `test fixture phrase is fully supported`() {
        val r = ImeCharMap.map(ImeCharMap.TEST_PHRASE)
        assertTrue(
            "test phrase '${ImeCharMap.TEST_PHRASE}' must be fully supported; rejected: ${r.rejected}",
            r.allAccepted,
        )
        assertEquals(ImeCharMap.TEST_PHRASE.length, r.acceptedCount)
    }

    @Test fun `unsupported characters are rejected not substituted`() {
        // Accented, CJK, emoji — outside the US-layout supported set.
        val unsupported = "àâñé中文😀"
        val r = ImeCharMap.map(unsupported)
        assertFalse(r.allAccepted)
        assertEquals(0, r.accepted.size)
        assertEquals(unsupported.length, r.rejected.size)
        // Each rejected codepoint is reported (no silent substitution).
        for (i in unsupported.indices) {
            assertEquals(unsupported[i].code, r.rejected[i])
        }
    }

    @Test fun `mixed supported and unsupported are partially accepted`() {
        val r = ImeCharMap.map("abàcd")
        assertEquals(4, r.accepted.size) // a, b, c, d
        assertEquals(1, r.rejected.size) // à
        assertEquals('à'.code, r.rejected[0])
    }

    @Test fun `empty commit is a no-op`() {
        val r = ImeCharMap.map("")
        assertTrue(r.allAccepted)
        assertEquals(0, r.accepted.size)
        assertEquals(0, r.rejected.size)
    }

    @Test fun `commit beyond max length is truncated and overflow reported`() {
        val long = "a".repeat(ImeCharMap.MAX_COMMIT_LENGTH + 10)
        val r = ImeCharMap.map(long)
        assertEquals(ImeCharMap.MAX_COMMIT_LENGTH, r.accepted.size)
        // The overflow marker is the first rejected entry.
        assertEquals(ImeCharMap.MAX_COMMIT_LENGTH, r.rejected[0])
    }

    @Test fun `keyEvents builds shift-modifier pair for uppercase`() {
        val m = ImeCharMap.map("A").accepted[0]
        // On host JVM the KeyEvent fields are stubs (isReturnDefaultValues),
        // so assert only on the Mapping structure, not KeyEvent internals.
        assertEquals(KeyEvent.KEYCODE_A, m.keyCode)
        assertTrue(m.shift)
        // keyEvents returns a non-null pair (DOWN, UP) for any valid mapping.
        val (down, up) = ImeCharMap.keyEvents(m, 1000L)
        assertTrue("DOWN event must be constructed", down !== null)
        assertTrue("UP event must be constructed", up !== null)
    }

    @Test fun `supported chars set includes test phrase`() {
        for (c in ImeCharMap.TEST_PHRASE) {
            assertTrue(
                "test phrase char '$c' (code ${c.code}) must be in supportedChars",
                c in ImeCharMap.supportedChars,
            )
        }
    }
}
