package com.pocketrealm.ui

import org.json.JSONObject

/**
 * Pure presentation copy for the friend-access invite pane's refusal
 * states — the same shape as LanPresenceModel:
 * no Compose, no service types, host-unit-testable.
 *
 * The refusal copy derives from the SERVICE'S STRUCTURED refusal (the
 * `error` field of the JSON answer), never a guess: a cap refusal and a
 * supervisor-unreachable failure are different user situations with
 * different remedies, and the old single line ("open-invite cap of 8?")
 * misreported a dead supervisor as the cap.
 */
object FriendInviteStatusCopy {

    /** The status line for a create-invite outcome: null on success.
     *
     *  [response] is the parsed service answer, or null when the call
     *  itself failed (supervisor unreachable / transport error) — the two
     *  cases a UI must never confuse. */
    fun statusFor(response: JSONObject?): String? {
        if (response == null) {
            return "Could not reach the realm supervisor — is a realm running? " +
                "Check the Home screen, then try again."
        }
        if (response.optBoolean("ok")) return null
        return when (response.optString("error")) {
            "open-invite-cap" ->
                // revocation lives on the Friend access card
                // (Android LanScreen; desktop Players screen) — the desktop
                // Guests card only creates guest accounts and Android has
                // no Guests card, so the remedy must name THIS card's own
                // invite list.
                "Invite refused: the open-invite cap is reached — revoke " +
                    "an open invite from the invite list on this Friend " +
                    "access card, then try again."
            else ->
                "Invite refused (${response.optString("error", "unknown reason")}) — " +
                    "check the realm supervisor logs, then try again."
        }
    }
}
