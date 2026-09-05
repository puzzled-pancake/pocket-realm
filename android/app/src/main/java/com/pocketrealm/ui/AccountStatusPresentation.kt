package com.pocketrealm.ui

import com.pocketrealm.supervisor.UserAccountStore

/** User-facing account failure copy. Stable machine codes stay in diagnostics/logs. */
internal fun accountProvisionFailureMessage(code: String, detail: String?): String = when (code) {
    "ACCOUNT_INVALID" -> when (detail) {
        "invalid username" -> "Account name must be 1–16 letters or numbers."
        "invalid password" -> "Password must be 1–16 letters or numbers."
        else -> "Check the account name and password. Use 1–16 letters or numbers for each."
    }
    "ACCOUNT_PASSWORD_MISMATCH" ->
        "That account already exists, but this password does not match it."
    "WORLD_NOT_READY" ->
        "The world is still starting. Wait until the realm says online, then try again."
    "WORLD_NOT_OWNED" ->
        "The local world session changed. Save and stop the realm, restart it, then try again."
    "ACCOUNT_REJECTED" ->
        "The realm rejected those account details. Try another account name."
    "ACCOUNT_CONTROL_FAILED" ->
        "The realm account service did not respond. Wait a moment and try again."
    else -> "The account could not be created. Try again or open diagnostics for technical details."
}

/** Launch must never overtake an account record that is still being replaced or removed. */
internal fun canLaunchGameWithAccount(
    autoLoginOnLaunch: Boolean,
    storedAccount: String?,
    accountOperationPending: Boolean,
): Boolean = !accountOperationPending && (!autoLoginOnLaunch || storedAccount != null)

/**
 * F2: per-keystroke validation for one Home account-form field. Reuses the
 * realm's own rule ([UserAccountStore.isValidCredential] — the rule already
 * exists end-to-end; this is presentation, not a fifth validator) so the
 * form can never disagree with the provision path. Mirrors the BotsScreen
 * preset-name dialog: an empty draft is never red, only a malformed draft
 * is — nothing has been submitted yet.
 */
internal fun accountCredentialFieldError(value: String): String? =
    if (value.isNotEmpty() && !UserAccountStore.isValidCredential(value)) {
        "Use 1–16 letters or numbers — no spaces, symbols, or accents"
    } else {
        null
    }

/** True when both fields satisfy the realm rule (so both are non-blank and well-formed). */
internal fun accountCredentialsSubmittable(username: String, password: String): Boolean =
    UserAccountStore.isValidCredential(username) && UserAccountStore.isValidCredential(password)

/**
 * F2: realm-readiness gates ONLY the Create action — the fields stay
 * editable while the realm is idle because the backend accepts account
 * creation in the WORLD_READY and CLIENT_FAILED phases (the UI used to be
 * stricter than the backend and hid/disabled the whole form). The
 * [accountOperationPending] disable is load-bearing (no double submit, no
 * edits mid-flight) and stays.
 */
internal fun accountCreateEnabled(
    realmReady: Boolean,
    accountOperationPending: Boolean,
    username: String,
    password: String,
): Boolean = realmReady && !accountOperationPending &&
    accountCredentialsSubmittable(username, password)

/**
 * F3: the Home Active-setup population chip. The "(grows from M)" suffix —
 * the admission ramp — renders only when `initialTarget < selectedTarget`;
 * profiles that start at their full target have no ramp to advertise.
 */
internal fun botCountChipLabel(initialTarget: Int, selectedTarget: Int): String =
    if (initialTarget < selectedTarget) {
        "$selectedTarget bots (grows from $initialTarget)"
    } else {
        "$selectedTarget bots"
    }
