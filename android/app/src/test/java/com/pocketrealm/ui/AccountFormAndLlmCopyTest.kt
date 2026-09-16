package com.pocketrealm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI contract tests: the Home account form's pure presentation
 * logic (field validation, Create gating, control-failure copy) and the
 * pinned LLM-screen copy (the lane-neutral speech hint and the
 * cloud-conversation spend disclosure).
 */
class AccountFormAndLlmCopyTest {

    // ---- per-keystroke field validation (reuses the realm rule) ----

    @Test
    fun fieldValidationLeavesEmptyDraftsAloneAndFlagsMalformedOnes() {
        // Nothing submitted yet — an empty draft is never red.
        assertNull(accountCredentialFieldError(""))
        assertNull(accountCredentialFieldError("player"))
        assertNull(accountCredentialFieldError("Player1"))
        assertNull(accountCredentialFieldError("a".repeat(16)))
        // The realm rule is 1..16 ASCII alphanumeric; these are the real
        // error shapes a keyboard can produce.
        assertNotNull(accountCredentialFieldError("has space"))
        assertNotNull(accountCredentialFieldError("sym!bol"))
        assertNotNull(accountCredentialFieldError("pass-word"))
        assertNotNull(accountCredentialFieldError("ümmlaut"))
        assertNotNull(accountCredentialFieldError("a".repeat(17)))
    }

    @Test
    fun fieldValidationCopyStatesTheRuleWithoutMachineCodes() {
        val copy = accountCredentialFieldError("has space")
        assertNotNull(copy)
        assertTrue(copy!!.contains("1–16"))
        assertTrue(copy.contains("letters or numbers"))
        // No exception class names, no internal codes — validation copy
        // keeps them out too.
        assertFalse(copy.contains("simpleName"))
        assertFalse(copy.contains("isValidCredential"))
    }

    @Test
    fun createButtonNeedsRealmReadinessValidityAndNoPendingOperation() {
        // Realm-readiness moved from the field gating into the button.
        assertFalse(accountCreateEnabled(
            realmReady = false, accountOperationPending = false,
            username = "player", password = "secret",
        ))
        // The pending-operation disable is load-bearing and stays.
        assertFalse(accountCreateEnabled(
            realmReady = true, accountOperationPending = true,
            username = "player", password = "secret",
        ))
        // Blank or malformed drafts are not submittable.
        assertFalse(accountCreateEnabled(
            realmReady = true, accountOperationPending = false,
            username = "", password = "secret",
        ))
        assertFalse(accountCreateEnabled(
            realmReady = true, accountOperationPending = false,
            username = "has space", password = "secret",
        ))
        assertFalse(accountCreateEnabled(
            realmReady = true, accountOperationPending = false,
            username = "player", password = "a".repeat(17),
        ))
        assertTrue(accountCreateEnabled(
            realmReady = true, accountOperationPending = false,
            username = "player", password = "secret",
        ))
    }

    @Test
    fun controlChannelFailuresUseTheFriendlyCopyNotTheExceptionClass() {
        // The failure copy must use the stable message, never the exception
        // class name.
        val copy = accountProvisionFailureMessage("ACCOUNT_CONTROL_FAILED", null)
        assertTrue(copy.contains("realm account service"))
        assertFalse(copy.contains("simpleName"))
        assertFalse(copy.contains("Exception"))
    }

    // ---- the population chip admits the ramp only when there is one ----

    @Test
    fun populationChipAddsTheRampOnlyWhileGrowing() {
        assertEquals("400 bots (grows from 25)", botCountChipLabel(initialTarget = 25, selectedTarget = 400))
        // Profiles that start at their full target have no ramp to advertise.
        assertEquals("80 bots", botCountChipLabel(initialTarget = 80, selectedTarget = 80))
    }

    // ---- LLM-screen copy pins ----

    @Test
    fun speechHintCoversNameAddressingTierPoolsAndTheAdmissionRamp() {
        val text = LLM_SPEECH_HINT.lowercase()
        // Name-addressing is the both-lane hard trigger (whisper works too).
        assertTrue(text.contains("name"))
        assertTrue(text.contains("whisper"))
        // Tier pools: strangers draw the short greeting lines.
        assertTrue(text.contains("strangers"))
        // The admission ramp grows the population over the first minutes.
        assertTrue(text.contains("grows"))
        assertTrue(text.contains("minutes"))
    }

    @Test
    fun cloudChatterDisclosureCarriesTheSpendAndRestartFacts() {
        val text = LLM_CLOUD_CHATTER_SUPPORT.lowercase()
        // spend-disclosure clause, verbatim:
        assertTrue("bot chat, including your messages, is sent to your configured external provider" in text)
        // Token-scale hint, prompt-dominated:
        assertTrue("1–1.5m tokens" in text)
        assertTrue("prompt" in text)
        // Restart note + per-session quota semantics:
        assertTrue("applies on the next realm start" in text)
        assertTrue("daily quotas are per-session" in text)
    }
}
