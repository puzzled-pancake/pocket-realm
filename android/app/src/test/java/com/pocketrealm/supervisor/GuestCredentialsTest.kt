package com.pocketrealm.supervisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** D5a (design-lan §4.2): the shared guest password generator's pins. */
class GuestCredentialsTest {

    @Test fun `password is sixteen symbols from the unambiguous alphabet`() {
        val generated = GuestCredentials.generatePassword()
        assertEquals(GuestCredentials.LENGTH, generated.length)
        generated.forEach { symbol ->
            assertTrue(
                "symbol $symbol is outside the alphabet",
                symbol in GuestCredentials.ALPHABET,
            )
        }
    }

    @Test fun `alphabet carries no confusable symbols and exactly 32 entries`() {
        assertEquals(32, GuestCredentials.ALPHABET.length)
        "0Oo1lI".forEach { banned ->
            assertTrue(
                "confusable symbol $banned must not be in the alphabet",
                banned !in GuestCredentials.ALPHABET,
            )
        }
    }

    @Test fun `alphabet is ascii alnum so the relay accepts the password`() {
        GuestCredentials.ALPHABET.forEach { symbol ->
            assertTrue(symbol.isLetterOrDigit() && symbol.code < 128)
        }
    }

    @Test fun `deterministic source drives the output and entropy is 80 bits`() {
        var next = 0
        val generated = GuestCredentials.generatePassword { next++ % GuestCredentials.ALPHABET.length }
        assertEquals("abcdefghijkmnpqr", generated)
        // 32 symbols^16 characters = 2^(16*5) — exactly 80 bits.
        assertEquals(80, GuestCredentials.LENGTH * 5)
    }

    @Test fun `two generated passwords differ with overwhelming likelihood`() {
        val first = GuestCredentials.generatePassword()
        val second = GuestCredentials.generatePassword()
        assertTrue(first != second)
    }
}
