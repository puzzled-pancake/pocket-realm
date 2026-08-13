package com.pocketrealm.supervisor

/**
 * Pure auto-login gate decision (O23). Extracted from
 * `AndroidRuntimeBackend.startClient` so the truth table is unit-testable on the
 * host JVM without loading Android classes.
 *
 * The integrated client auto-logs in when the user has enabled the master switch
 * AND a user-chosen identity has been provisioned through [UserAccountStore].
 * Bot profiles never synthesize or rotate a hidden fallback identity: without a
 * saved account the client opens at the login screen and remains user-controlled.
 */
object AutoLoginPolicy {
    fun resolveAutoLogin(
        profileId: String,
        autoLoginOnLaunch: Boolean,
        userAccountProvisioned: Boolean,
        isBotProfile: (String) -> Boolean,
    ): Decision {
        if (!autoLoginOnLaunch) return Decision(off = true)
        if (userAccountProvisioned) return Decision(singlePlayerAutoLogin = true)
        return Decision(off = true)
    }

    data class Decision(
        val off: Boolean = false,
        val singlePlayerAutoLogin: Boolean = false,
        /** Retained as a stable control-model field; automatic provisioning is permanently disabled. */
        val runBotProvisioning: Boolean = false,
    )
}

/** Pure acceptance rule for the one live world response that authorizes credential injection. */
object AutoLoginCredentialProof {
    enum class Disposition {
        ACCEPT,
        INVALID_CREDENTIAL,
        AUTHORITY_UNAVAILABLE,
    }

    /**
     * Only an answer from the exact live world owner is authoritative enough
     * to reject a stored credential. Transport/control failures and ownership
     * changes must preserve the user's saved account for a later retry.
     */
    fun evaluate(
        expectedOwner: ComponentOwner,
        expectedAccountId: Long,
        expectedGmLevel: Int,
        responseOk: Boolean,
        passwordVerified: Boolean,
        accountExists: Boolean,
        accountId: Long,
        gmLevel: Int,
        ownerSessionId: String?,
        ownerInstanceToken: String?,
    ): Disposition {
        if (!responseOk ||
            ownerSessionId != expectedOwner.sessionId ||
            ownerInstanceToken != expectedOwner.instanceToken
        ) return Disposition.AUTHORITY_UNAVAILABLE

        return if (passwordVerified && accountExists &&
            accountId == expectedAccountId && gmLevel == expectedGmLevel
        ) Disposition.ACCEPT else Disposition.INVALID_CREDENTIAL
    }

    fun accepts(
        expectedOwner: ComponentOwner,
        expectedAccountId: Long,
        expectedGmLevel: Int,
        responseOk: Boolean,
        passwordVerified: Boolean,
        accountExists: Boolean,
        accountId: Long,
        gmLevel: Int,
        ownerSessionId: String?,
        ownerInstanceToken: String?,
    ): Boolean = evaluate(
        expectedOwner = expectedOwner,
        expectedAccountId = expectedAccountId,
        expectedGmLevel = expectedGmLevel,
        responseOk = responseOk,
        passwordVerified = passwordVerified,
        accountExists = accountExists,
        accountId = accountId,
        gmLevel = gmLevel,
        ownerSessionId = ownerSessionId,
        ownerInstanceToken = ownerInstanceToken,
    ) == Disposition.ACCEPT
}
