package com.pocketrealm.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStatusPresentationTest {
    @Test fun invalidFieldsExplainTheActualCorrectionWithoutInternalCodes() {
        val username = accountProvisionFailureMessage("ACCOUNT_INVALID", "invalid username")
        val password = accountProvisionFailureMessage("ACCOUNT_INVALID", "invalid password")

        assertTrue(username.contains("Account name"))
        assertTrue(password.contains("Password"))
        assertFalse(username.contains("ACCOUNT_INVALID"))
        assertFalse(password.contains("GM 0"))
    }

    @Test fun readinessAndUnknownFailuresGiveAnAction() {
        assertTrue(accountProvisionFailureMessage("WORLD_NOT_READY", null).contains("Wait"))
        assertTrue(accountProvisionFailureMessage("NEW_INTERNAL_CODE", null).contains("diagnostics"))
    }

    @Test fun accountPersistenceBlocksLaunchUntilTheRecordIsDurable() {
        assertFalse(canLaunchGameWithAccount(
            autoLoginOnLaunch = true,
            storedAccount = "player",
            accountOperationPending = true,
        ))
        assertTrue(canLaunchGameWithAccount(
            autoLoginOnLaunch = true,
            storedAccount = "player",
            accountOperationPending = false,
        ))
        assertFalse(canLaunchGameWithAccount(
            autoLoginOnLaunch = true,
            storedAccount = null,
            accountOperationPending = false,
        ))
        assertTrue(canLaunchGameWithAccount(
            autoLoginOnLaunch = false,
            storedAccount = null,
            accountOperationPending = false,
        ))
    }
}
