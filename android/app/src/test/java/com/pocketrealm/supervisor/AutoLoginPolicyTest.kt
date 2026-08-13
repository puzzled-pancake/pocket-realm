package com.pocketrealm.supervisor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM truth-table test for [AutoLoginPolicy.resolveAutoLogin]. Pure logic:
 * no Android classes loaded.
 */
class AutoLoginPolicyTest {
    private val botProfile = "bot-baseline"
    private val integratedProfile = "integrated"
    private fun policy(
        profileId: String,
        autoLoginOnLaunch: Boolean,
        userAccountProvisioned: Boolean,
    ) = AutoLoginPolicy.resolveAutoLogin(
        profileId = profileId,
        autoLoginOnLaunch = autoLoginOnLaunch,
        userAccountProvisioned = userAccountProvisioned,
        isBotProfile = { it == botProfile },
    )

    @Test fun `master switch off never auto-logs in`() {
        val d = policy(botProfile, autoLoginOnLaunch = false, userAccountProvisioned = false)
        assertTrue(d.off)
        assertFalse(d.singlePlayerAutoLogin)
        assertFalse(d.runBotProvisioning)
    }

    @Test fun `user account provisioned logs in without bot provisioning`() {
        val d = policy(integratedProfile, autoLoginOnLaunch = true, userAccountProvisioned = true)
        assertTrue(d.singlePlayerAutoLogin)
        assertFalse(d.runBotProvisioning)
        assertFalse(d.off)
    }

    @Test fun `bot profile with no user account remains at manual login`() {
        val d = policy(botProfile, autoLoginOnLaunch = true, userAccountProvisioned = false)
        assertTrue(d.off)
        assertFalse(d.singlePlayerAutoLogin)
        assertFalse(d.runBotProvisioning)
    }

    @Test fun `non-bot integrated profile without user account does not log in`() {
        val d = policy(integratedProfile, autoLoginOnLaunch = true, userAccountProvisioned = false)
        assertTrue(d.off)
        assertFalse(d.singlePlayerAutoLogin)
    }

    @Test fun `user account takes precedence over bot profile`() {
        val d = policy(botProfile, autoLoginOnLaunch = true, userAccountProvisioned = true)
        assertTrue(d.singlePlayerAutoLogin)
        assertFalse(d.runBotProvisioning)
    }

    @Test fun `live proof requires password identity privilege and exact world owner`() {
        val owner = ComponentOwner("session-a", "token-a")
        fun accepts(
            passwordVerified: Boolean = true,
            accountId: Long = 42,
            gmLevel: Int = 0,
            session: String? = "session-a",
            token: String? = "token-a",
        ) = AutoLoginCredentialProof.accepts(
            expectedOwner = owner,
            expectedAccountId = 42,
            expectedGmLevel = 0,
            responseOk = true,
            passwordVerified = passwordVerified,
            accountExists = true,
            accountId = accountId,
            gmLevel = gmLevel,
            ownerSessionId = session,
            ownerInstanceToken = token,
        )

        assertTrue(accepts())
        assertFalse(accepts(passwordVerified = false))
        assertFalse(accepts(accountId = 43))
        assertFalse(accepts(gmLevel = 1))
        assertFalse(accepts(session = "session-b"))
        assertFalse(accepts(token = "token-b"))

        assertTrue(AutoLoginCredentialProof.accepts(
            expectedOwner = owner,
            expectedAccountId = 42,
            expectedGmLevel = 3,
            responseOk = true,
            passwordVerified = true,
            accountExists = true,
            accountId = 42,
            gmLevel = 3,
            ownerSessionId = "session-a",
            ownerInstanceToken = "token-a",
        ))
        assertFalse(AutoLoginCredentialProof.accepts(
            expectedOwner = owner,
            expectedAccountId = 42,
            expectedGmLevel = 3,
            responseOk = true,
            passwordVerified = true,
            accountExists = true,
            accountId = 42,
            gmLevel = 0,
            ownerSessionId = "session-a",
            ownerInstanceToken = "token-a",
        ))
    }

    @Test fun `only exact world authority may invalidate a stored account`() {
        val worldOwner = ComponentOwner("realm-session", "world-token")
        fun disposition(
            ok: Boolean = true,
            password: Boolean = true,
            exists: Boolean = true,
            id: Long = 42,
            gm: Int = 0,
            session: String? = "realm-session",
            token: String? = "world-token",
        ) = AutoLoginCredentialProof.evaluate(
            expectedOwner = worldOwner,
            expectedAccountId = 42,
            expectedGmLevel = 0,
            responseOk = ok,
            passwordVerified = password,
            accountExists = exists,
            accountId = id,
            gmLevel = gm,
            ownerSessionId = session,
            ownerInstanceToken = token,
        )

        assertTrue(disposition() == AutoLoginCredentialProof.Disposition.ACCEPT)
        assertTrue(disposition(password = false) ==
            AutoLoginCredentialProof.Disposition.INVALID_CREDENTIAL)
        assertTrue(disposition(exists = false) ==
            AutoLoginCredentialProof.Disposition.INVALID_CREDENTIAL)
        assertTrue(disposition(id = 99) ==
            AutoLoginCredentialProof.Disposition.INVALID_CREDENTIAL)
        assertTrue(disposition(gm = 3) ==
            AutoLoginCredentialProof.Disposition.INVALID_CREDENTIAL)
        assertTrue(disposition(ok = false) ==
            AutoLoginCredentialProof.Disposition.AUTHORITY_UNAVAILABLE)
        assertTrue(disposition(token = "client-token") ==
            AutoLoginCredentialProof.Disposition.AUTHORITY_UNAVAILABLE)
        assertTrue(disposition(session = null) ==
            AutoLoginCredentialProof.Disposition.AUTHORITY_UNAVAILABLE)
    }
}
