package com.pocketrealm.discovery.iroh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The unlisted game's invite code: PR1- + base64url of the join
 * descriptor's frozen JSON, decoded through the descriptor's own refusal
 * rules. */
class InviteCodeTest {

    private val descriptor = IrohJoinDescriptor(
        nodeHex = "ab".repeat(32),
        relayUrl = "https://relay.example.com/",
    )

    @Test
    fun roundTripsThroughThePrefixAndBase64Url() {
        val code = InviteCode.encode(descriptor)
        assertTrue(code.startsWith("PR1-"))
        assertFalse("base64url never carries + or /", code.drop(4).any { it == '+' || it == '/' })
        assertEquals(descriptor, InviteCode.decode(code))
        assertTrue(InviteCode.looksLikeInviteCode(code))
        assertFalse(InviteCode.looksLikeInviteCode("192.168.1.20"))
    }

    @Test
    fun rejectsJunk() {
        for (junk in listOf(
            "PR1-%%%not-base64%%%///",
            "PR1-" + java.util.Base64.getUrlEncoder().encodeToString("not json".toByteArray()),
            // A JSON object that is not a descriptor fails the descriptor's
            // own rules (unknown keys).
            "PR1-" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"type\":\"other\"}".toByteArray()),
        )) {
            assertTrue("junk refused: $junk", runCatching { InviteCode.decode(junk) }.isFailure)
        }
    }

    @Test
    fun rejectsAnOversizedCode() {
        val big = "PR1-" + "a".repeat(InviteCode.MAX_CODE_LENGTH)
        assertTrue(runCatching { InviteCode.decode(big) }.isFailure)
    }

    @Test
    fun rejectsAWrongPrefixAndAnEmptyBody() {
        assertTrue(runCatching { InviteCode.decode("PR2-" + InviteCode.encode(descriptor).drop(4)) }.isFailure)
        assertTrue(runCatching { InviteCode.decode("PR1-") }.isFailure)
        assertTrue(runCatching { InviteCode.decode("") }.isFailure)
    }

    @Test
    fun carriesNoPasswordOrAddress() {
        // The code is the join descriptor only: a host password or an
        // address never has a place in it.
        val code = InviteCode.encode(descriptor)
        assertFalse("sesame" in code)
        assertFalse("127.0.0.1" in code)
        assertFalse("192.168" in code)
    }
}
