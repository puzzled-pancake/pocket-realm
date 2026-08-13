package com.pocketrealm.ui

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
