package com.pocketrealm.desktop

import com.pocketrealm.supervisor.AccountProvisionResult
import com.pocketrealm.supervisor.AutoLoginCredentialProof
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.UserAccountStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * DESK-57: the desktop auto-login credential lifecycle — the disposition
 * table (the world's READ-ONLY answer × stored identity × live world
 * owner → the shared AutoLoginCredentialProof verdict). The proof never
 * provisions: a stored join-friend credential can never mint a phantom
 * local account, and an INVALID_CREDENTIAL verdict KEEPS the record —
 * exactly the Android disposition ("The saved account was kept").
 */
class DesktopAutoLoginProofTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sessionOwner = ComponentOwner("session-id", "instance-token")

    private fun disposition(
        provision: AccountProvisionResult,
        storedAccountId: Long = 7,
        storedGmLevel: Int = 0,
        expectedOwner: ComponentOwner? = sessionOwner,
        reportedOwner: ComponentOwner? = sessionOwner,
    ): AutoLoginCredentialProof.Disposition =
        DesktopAppModel.credentialProofDisposition(
            storedAccountId = storedAccountId,
            storedGmLevel = storedGmLevel,
            expectedOwner = expectedOwner,
            reportedOwner = reportedOwner,
            provision = provision,
        )

    private fun provision(
        code: String,
        ok: Boolean = false,
        accountId: Long = 0,
        gmLevel: Int = 0,
    ): AccountProvisionResult = AccountProvisionResult(
        ok = ok,
        code = code,
        accountId = accountId,
        gmLevel = gmLevel,
        detail = code,
    )

    // ---- the disposition table -----------------------------------------

    @Test
    fun aVerifiedMatchingIdentityIsAccepted() {
        assertEquals(
            AutoLoginCredentialProof.Disposition.ACCEPT,
            disposition(
                provision("ACCOUNT_VERIFIED", ok = true, accountId = 7, gmLevel = 0),
            ),
        )
    }

    @Test
    fun aVerifiedButChangedRowIsRejected() {
        assertEquals(
            AutoLoginCredentialProof.Disposition.INVALID_CREDENTIAL,
            disposition(
                provision("ACCOUNT_VERIFIED", ok = true, accountId = 8, gmLevel = 0),
                storedAccountId = 7,
            ),
        )
        assertEquals(
            AutoLoginCredentialProof.Disposition.INVALID_CREDENTIAL,
            disposition(
                provision("ACCOUNT_VERIFIED", ok = true, accountId = 7, gmLevel = 2),
                storedGmLevel = 0,
            ),
        )
    }

    @Test
    fun aPasswordMismatchIsTheStaleCredentialVerdict() {
        assertEquals(
            AutoLoginCredentialProof.Disposition.INVALID_CREDENTIAL,
            disposition(provision("ACCOUNT_PASSWORD_MISMATCH")),
        )
    }

    @Test
    fun anUnknownIdentityIsTheStaleCredentialVerdict() {
        // The world never knew the identity — the READ-ONLY answer (no row
        // is fabricated): the stored record no longer describes the realm's
        // row set → INVALID_CREDENTIAL, record kept, one manual create
        // re-syncs it.
        assertEquals(
            AutoLoginCredentialProof.Disposition.INVALID_CREDENTIAL,
            disposition(provision("ACCOUNT_UNKNOWN")),
        )
    }

    @Test
    fun theProvisionOnlyOutcomeIsNeverAuthoritativeForTheProof() {
        // ACCOUNT_CREATED is a provision leg's answer. The proof leg is
        // read-only and can no longer produce it; if provisioning ever
        // leaked back into the proof, its answer must not reject a stored
        // record (keep-side) — the phantom-mint path stays dead.
        assertEquals(
            AutoLoginCredentialProof.Disposition.AUTHORITY_UNAVAILABLE,
            disposition(provision("ACCOUNT_CREATED", ok = true, accountId = 12, gmLevel = 0)),
        )
    }

    @Test
    fun transportClassOutcomesNeverRejectAStoredCredential() {
        listOf(
            "WORLD_NOT_READY",
            "WORLD_NOT_OWNED",
            "ACCOUNT_CONTROL_FAILED",
            "ACCOUNT_REJECTED",
            "ACCOUNT_INVALID",
        ).forEach { code ->
            assertEquals(
                "code $code must keep the record",
                AutoLoginCredentialProof.Disposition.AUTHORITY_UNAVAILABLE,
                disposition(provision(code)),
            )
        }
    }

    @Test
    fun anOwnerMismatchOrMissingOwnerNeverRejects() {
        assertEquals(
            AutoLoginCredentialProof.Disposition.AUTHORITY_UNAVAILABLE,
            disposition(
                provision("ACCOUNT_PASSWORD_MISMATCH"),
                reportedOwner = ComponentOwner("other-session", "other-token"),
            ),
        )
        assertEquals(
            AutoLoginCredentialProof.Disposition.AUTHORITY_UNAVAILABLE,
            disposition(provision("ACCOUNT_VERIFIED", ok = true, accountId = 7), expectedOwner = null),
        )
        assertEquals(
            AutoLoginCredentialProof.Disposition.AUTHORITY_UNAVAILABLE,
            disposition(provision("ACCOUNT_VERIFIED", ok = true, accountId = 7), reportedOwner = null),
        )
    }

    @Test
    fun aStaleVerdictNeverFailsTheClientLaunchOnEitherPlatform() {
        // The cross-flow pin the completion ledger asked for: the shared
        // hand-off seam (compiled into BOTH twins) yields NO credential for
        // a stale verdict — this model returns to manual login and the
        // Android startClient skips auto-login the same way, so neither
        // platform may turn a stale saved account into a failed client
        // start. The record itself stays on disk for explicit correction.
        val stored = UserAccountStore(folder.newFolder("user-account-stale")).save(
            "Tester1", "Passw0rd1", accountId = 7,
        )
        assertSame(
            stored,
            AutoLoginCredentialProof.credentialForLaunch(
                AutoLoginCredentialProof.Disposition.ACCEPT,
                stored,
            ),
        )
        assertNull(
            "a stale record injects no credential - manual login, launch continues",
            AutoLoginCredentialProof.credentialForLaunch(
                AutoLoginCredentialProof.Disposition.INVALID_CREDENTIAL,
                stored,
            ),
        )
    }

    // ---- the quarantine leg ---------------------------------------------

    @Test
    fun clearSweepsTheQuarantineCopiesToo() {
        // The user's explicit removal must remove the SECRET, not just the
        // active slot: every account.invalid.*.json quarantine copy carries
        // the full plaintext username+password record. The copies come from
        // the live corrupt-load quarantine path — the only unintentional
        // transition that still produces them (an INVALID_CREDENTIAL verdict
        // KEEPS the record, per the disposition above).
        val directory: File = folder.newFolder("user-account-clear")
        val store = UserAccountStore(directory)
        repeat(2) {
            store.save("Tester1", "Passw0rd1", accountId = 7)
            File(directory, "account.json").writeText("{ not a record")
            assertNull("a corrupt load must quarantine and come back empty", store.loadOrQuarantine())
        }
        assertEquals(
            2,
            directory.listFiles { file -> file.name.startsWith("account.invalid.") }!!.size,
        )
        store.save("Tester1", "Passw0rd1", accountId = 11)

        store.clear()

        assertNull("the active slot must be empty", store.loadProvisioned())
        assertEquals(
            "an explicit removal leaves no plaintext copy behind",
            0,
            directory.listFiles { file -> file.name.startsWith("account.invalid.") }!!.size,
        )
    }
}
