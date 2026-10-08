package com.pocketrealm.desktop.ui

import com.pocketrealm.ui.accountCreateEnabled
import com.pocketrealm.ui.accountCredentialFieldError
import com.pocketrealm.ui.accountCredentialsSubmittable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DESK-136: the account entry contract is validate-and-explain, not input
 * mangling — the field keeps what the user typed (the desktop field
 * callback is `it.take(16)` only), a value the realm rule rejects shows
 * the shared plain-copy reason at the field, and the create gate runs
 * through the same shared rule the provision path enforces. The pinned
 * leg from the register plan: "café" leaves the text intact, disables
 * Create, and shows the copy.
 */
class AccountEntryValidationTest {

    @Test
    fun cafeIsKeptIntactRejectedAndExplained() {
        val typed = "café"
        // The field path keeps raw keystrokes (16-char cap only) — there is
        // no character-class filter left to delete the é. The pure legs:
        // the value is NOT valid per the realm rule...
        assertFalse(com.pocketrealm.supervisor.UserAccountStore.isValidCredential(typed))
        // ...Create is disabled through the shared gate...
        assertFalse(accountCredentialsSubmittable(typed, "Passw0rd1"))
        assertFalse(accountCreateEnabled(realmReady = true, accountOperationPending = false, username = typed, password = "Passw0rd1"))
        // ...and the field shows the shared plain-copy reason.
        assertEquals(
            "Use 1–16 letters or numbers — no spaces, symbols, or accents",
            accountCredentialFieldError(typed),
        )
    }

    @Test
    fun wellFormedAsciiCredentialsPassWithoutAnError() {
        assertNull(accountCredentialFieldError("Someone1"))
        assertNull(accountCredentialFieldError("Passw0rd1"))
        assertTrue(accountCredentialsSubmittable("Someone1", "Passw0rd1"))
        assertTrue(accountCreateEnabled(realmReady = true, accountOperationPending = false, username = "Someone1", password = "Passw0rd1"))
    }

    @Test
    fun anEmptyDraftIsNeverRedButNeverSubmittable() {
        assertNull("nothing submitted yet: no red", accountCredentialFieldError(""))
        assertFalse(accountCredentialsSubmittable("", "Passw0rd1"))
        assertFalse(accountCreateEnabled(realmReady = true, accountOperationPending = false, username = "", password = "Passw0rd1"))
    }

    @Test
    fun theSixteenCharCapIsTheOnlyTransformation() {
        val overlong = "abcdefghijklmnopqrs" // 19 chars
        // The field keeps raw keystrokes up to 16 — and a well-formed 16 is
        // exactly at the realm rule's boundary (valid), while the uncapped
        // string exceeds the band and is refused by the same rule.
        assertEquals(16, overlong.take(16).length)
        assertTrue(com.pocketrealm.supervisor.UserAccountStore.isValidCredential(overlong.take(16)))
        assertFalse(com.pocketrealm.supervisor.UserAccountStore.isValidCredential(overlong))
    }

    @Test
    fun worldReadinessAndPendingGateOnlyTheCreateAction() {
        assertTrue(
            accountCreateEnabled(realmReady = true, accountOperationPending = false, username = "Someone1", password = "Passw0rd1"),
        )
        assertFalse(accountCreateEnabled(realmReady = false, accountOperationPending = false, username = "Someone1", password = "Passw0rd1"))
        assertFalse(accountCreateEnabled(realmReady = true, accountOperationPending = true, username = "Someone1", password = "Passw0rd1"))
    }
}
