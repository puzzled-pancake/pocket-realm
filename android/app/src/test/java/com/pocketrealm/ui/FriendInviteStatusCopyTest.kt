package com.pocketrealm.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * the invite pane's refusal copy derives from the
 * SERVICE'S STRUCTURED refusal, never a guess. A cap refusal, a
 * supervisor-unreachable failure, and an unknown structured error are three
 * distinct user situations — assert each renders its OWN copy and neither
 * path shows the other's.
 */
class FriendInviteStatusCopyTest {

    @Test
    fun successAnswersNull() {
        assertNull(FriendInviteStatusCopy.statusFor(JSONObject("""{"ok":true,"inviteId":"FRD-1"}""")))
    }

    @Test
    fun capRefusalNamesTheCapAndTheRemedy() {
        val copy = FriendInviteStatusCopy.statusFor(
            JSONObject("""{"ok":false,"error":"open-invite-cap"}"""))
        assertEquals(
            "Invite refused: the open-invite cap is reached — revoke " +
                "an open invite from the invite list on this Friend " +
                "access card, then try again.",
            copy,
        )
        // the remedy must never point at the Guests card
        // (desktop: creates guest accounts only; Android: does not exist)
        // — revocation lives on the Friend access card.
        assertEquals(false, copy!!.contains("Guests"))
    }

    @Test
    fun unreachableSupervisorIsNeverReportedAsTheCap() {
        // response == null: the runCatching{}.getOrNull() transport failure
        val copy = FriendInviteStatusCopy.statusFor(null)
        assertEquals(true, copy!!.contains("Could not reach the realm supervisor"))
        assertEquals(false, copy.contains("cap"))
    }

    @Test
    fun unknownStructuredErrorPassesThroughHonestly() {
        val copy = FriendInviteStatusCopy.statusFor(
            JSONObject("""{"ok":false,"error":"store-unavailable"}"""))
        assertEquals(true, copy!!.contains("store-unavailable"))
        assertEquals(false, copy.contains("cap"))
    }

    // ---- the JOINER-side refusal line ----

    @Test
    fun `joiner refusal asserts no cause the protocol cannot know`() {
        val copy = joinerRefusalCopy()
        // the protocol makes refusals indistinguishable — no cause asserts
        assertEquals(false, copy.contains("wrong/expired"))
        assertEquals(false, copy.contains("wrong code"))
        assertEquals(false, copy.contains("decision window"))
        // the rate lock makes retry invitations harmful
        assertEquals(false, copy.contains("try again"))
        assertEquals(false, copy.contains("Ask for a new invite"))
    }

    @Test
    fun `joiner refusal carries the honest statement and stable remedy`() {
        val copy = joinerRefusalCopy()
        assertEquals(true, copy.contains("refused this request without a reason"))
        assertEquals(true, copy.contains("rate-limited"))
        assertEquals(true, copy.contains("Contact the host"))
    }
}
