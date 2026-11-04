package com.pocketrealm.supervisor

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The invite store (design-remote-accounts): the state machine, the open
 * cap, single-use tokens, the 15-minute decision window, the read-only poll
 * with its one-time accountId reveal, the audit trail, and the storage
 * secrecy rule — the raw token and the submitted (s, v) verifier never
 * touch the persisted file.
 */
class FriendInviteStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var nowMs = 1_000_000L

    private fun store(): FriendInviteStore =
        FriendInviteStore(folder.newFolder("friend-access"), clock = { nowMs })

    private fun validS() = "aa".repeat(32)
    private fun validV() = "bb".repeat(32)

    @Test
    fun createShowsTheTokenOnceAndPersistsOnlyItsHash() {
        val store = store()
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        assertTrue("human-readable id", created.inviteId.startsWith("FRD-"))
        assertTrue(
            "24 SecureRandom bytes base64url (no padding)",
            created.tokenBase64Url.length >= 32,
        )
        assertFalse("base64url alphabet", created.tokenBase64Url.any { it == '+' || it == '/' })

        val bytes = File(
            folder.root,
            "friend-access/${FriendInviteStore.STORE_FILE_NAME}",
        ).readText()
        assertTrue(bytes.contains(created.inviteId))
        assertFalse("the raw token must never be stored", created.tokenBase64Url in bytes)
        assertTrue(
            "the token hash is stored",
            FriendInviteStore.sha256Hex(created.tokenBase64Url) in bytes,
        )
        assertEquals("Dave", store.rows().single().note)
    }

    @Test
    fun generationIsRefusedBeyondTheOpenCap() {
        val store = store()
        repeat(FriendInviteStore.MAX_OPEN_INVITES) {
            assertTrue(store.create("n$it") is FriendInviteStore.CreateResult.Created)
        }
        val refused = store.create("one too many")
        assertTrue(refused is FriendInviteStore.CreateResult.Refused)
        assertEquals(FriendInviteStore.MAX_OPEN_INVITES, store.openCount())
        // The audit records the refusal, not a ninth invite.
        assertTrue(store.auditTail().any { it.optString("action") == FriendInviteStore.ACTION_CREATE_REFUSED })
    }

    @Test
    fun submitMovesOpenToSubmittedAndIsSingleUse() {
        val store = store()
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        val accepted = store.submit(
            created.inviteId, created.tokenBase64Url, "Dave", validS(), validV(), "192.168.1.77",
        )
        assertTrue(accepted is FriendInviteStore.SubmitResult.Accepted)
        val row = store.rows().single()
        assertEquals(FriendInviteStore.State.SUBMITTED, row.state)
        assertEquals("Dave", row.username)
        assertEquals("192.168.1.77", row.sourceIp)
        assertNotNull(row.expiresAtMs)

        // A second submit — even with the correct token — is a refusal.
        assertTrue(
            store.submit(created.inviteId, created.tokenBase64Url, "Dave", validS(), validV(), "192.168.1.77")
                is FriendInviteStore.SubmitResult.Refused,
        )
        // And so is a wrong token against the submitted row.
        assertTrue(
            store.submit(created.inviteId, "wrong-token", "Dave", validS(), validV(), "192.168.1.77")
                is FriendInviteStore.SubmitResult.Refused,
        )
    }

    @Test
    fun unknownInviteWrongTokenAndMalformedVerifierAllRefuse() {
        val store = store()
        assertTrue(
            store.submit("FRD-NOPE", "token", "Dave", validS(), validV(), "10.0.0.1")
                is FriendInviteStore.SubmitResult.Refused,
        )
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        assertTrue(
            store.submit(created.inviteId, "not-the-token", "Dave", validS(), validV(), "10.0.0.1")
                is FriendInviteStore.SubmitResult.Refused,
        )
        assertTrue(
            store.submit(created.inviteId, created.tokenBase64Url, "Dave", "ZZ".repeat(32), validV(), "10.0.0.1")
                is FriendInviteStore.SubmitResult.Refused,
        )
        assertTrue(
            store.submit(created.inviteId, created.tokenBase64Url, "Da ve", validS(), validV(), "10.0.0.1")
                is FriendInviteStore.SubmitResult.Refused,
        )
        // None of the refusals consumed the invite.
        assertEquals(FriendInviteStore.State.OPEN, store.rows().single().state)
    }

    @Test
    fun approveProvisionsAndRejectBurnsTheVerifier() {
        val store = store()
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        store.submit(created.inviteId, created.tokenBase64Url, "Dave", validS(), validV(), "192.168.1.5")

        val verifier = store.markApproved(created.inviteId)
        assertNotNull("the approval hands back the held verifier", verifier)
        assertEquals("Dave", verifier!!.username)
        assertEquals(validS(), verifier.sHex)
        assertEquals(FriendInviteStore.State.APPROVED, store.rows().single().state)

        store.markProvisioned(created.inviteId, accountId = 42L)
        val provisioned = store.rows().single()
        assertEquals(FriendInviteStore.State.PROVISIONED, provisioned.state)
        assertEquals(42L, provisioned.accountId)
    }

    @Test
    fun approvalFailureReturnsTheRowToSubmittedForRetry() {
        val store = store()
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        store.submit(created.inviteId, created.tokenBase64Url, "Dave", validS(), validV(), "192.168.1.5")
        store.markApproved(created.inviteId)
        store.approvalFailed(created.inviteId, "ACCOUNT_EXISTS")
        assertEquals(FriendInviteStore.State.SUBMITTED, store.rows().single().state)
    }

    @Test
    fun rejectDropsTheVerifierImmediately() {
        val store = store()
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        store.submit(created.inviteId, created.tokenBase64Url, "Dave", validS(), validV(), "192.168.1.5")
        assertTrue(store.reject(created.inviteId))
        assertEquals(FriendInviteStore.State.REJECTED, store.rows().single().state)
        // No verifier is available after the rejection, and the approval leg refuses.
        assertEquals(null, store.markApproved(created.inviteId))
    }

    @Test
    fun theDecisionWindowExpiresFifteenMinutesAfterSubmission() {
        val store = store()
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        store.submit(created.inviteId, created.tokenBase64Url, "Dave", validS(), validV(), "192.168.1.5")
        nowMs += FriendInviteStore.SUBMISSION_WINDOW_MS - 1_000
        assertEquals(FriendInviteStore.State.SUBMITTED, store.rows().single().state)
        nowMs += 2_000
        assertEquals(FriendInviteStore.State.EXPIRED, store.rows().single().state)
        // The expired row no longer approves and no longer polls.
        assertEquals(null, store.markApproved(created.inviteId))
        assertTrue(
            store.poll(created.inviteId, created.tokenBase64Url) is FriendInviteStore.PollResult.Refused,
        )
    }

    @Test
    fun pollRevealsTheAccountIdExactlyOnceThenBurnsTheToken() {
        val store = store()
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        val token = created.tokenBase64Url

        // Before submission a poll is a refusal (indistinct).
        assertTrue(store.poll(created.inviteId, token) is FriendInviteStore.PollResult.Refused)
        store.submit(created.inviteId, token, "Dave", validS(), validV(), "192.168.1.5")
        assertTrue(store.poll(created.inviteId, token) is FriendInviteStore.PollResult.Pending)
        store.markApproved(created.inviteId)
        assertTrue(store.poll(created.inviteId, token) is FriendInviteStore.PollResult.Pending)
        store.markProvisioned(created.inviteId, accountId = 7L)

        val revealed = store.poll(created.inviteId, token)
        assertTrue(revealed is FriendInviteStore.PollResult.Provisioned)
        assertEquals(7L, (revealed as FriendInviteStore.PollResult.Provisioned).accountId)

        // The reveal burned the token: a further poll is the refusal blob path.
        assertTrue(store.poll(created.inviteId, token) is FriendInviteStore.PollResult.Refused)
    }

    @Test
    fun theSubmittedVerifierIsNeverPersisted() {
        val store = store()
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        val sHex = "ab".repeat(32)
        val vHex = "cd".repeat(32)
        store.submit(created.inviteId, created.tokenBase64Url, "Dave", sHex, vHex, "192.168.1.5")
        // Force a persistence pass (any mutating call rewrites the file).
        store.markApproved(created.inviteId)
        store.approvalFailed(created.inviteId, "probe")
        val bytes = File(folder.root, "friend-access/${FriendInviteStore.STORE_FILE_NAME}").readText()
        assertFalse("sHex must never touch disk", sHex in bytes)
        assertFalse("vHex must never touch disk", vHex in bytes)
        assertFalse("the token must never touch disk", created.tokenBase64Url in bytes)
    }

    @Test
    fun auditRowsCarryActionsNotSecrets() {
        val store = store()
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        store.submit(created.inviteId, created.tokenBase64Url, "Dave", validS(), validV(), "192.168.1.9")
        store.reject(created.inviteId)
        val actions = store.auditTail().map { it.optString("action") }
        assertTrue(FriendInviteStore.ACTION_CREATED in actions)
        assertTrue(FriendInviteStore.ACTION_SUBMITTED in actions)
        assertTrue(FriendInviteStore.ACTION_REJECTED in actions)
        val raw = File(folder.root, "friend-access/${FriendInviteStore.AUDIT_FILE_NAME}").readText()
        assertFalse(validS() in raw)
        assertFalse(created.tokenBase64Url in raw)
        assertTrue(raw.contains("192.168.1.9"))
    }

    @Test
    fun revokeBurnsAnOpenInvite() {
        val store = store()
        val created = store.create("Dave") as FriendInviteStore.CreateResult.Created
        assertTrue(store.revoke(created.inviteId))
        assertEquals(FriendInviteStore.State.BURNED, store.rows().single().state)
        // The burned token no longer submits.
        assertTrue(
            store.submit(created.inviteId, created.tokenBase64Url, "Dave", validS(), validV(), "192.168.1.5")
                is FriendInviteStore.SubmitResult.Refused,
        )
    }

    @Test
    fun inviteIdsAreUniqueAcrossCreations() {
        val store = store()
        val ids = ArrayList<String>()
        // Roll past the open cap by revoking: 24 creations total, each new
        // id must be unique.
        repeat(24) {
            when (val created = store.create("n")) {
                is FriendInviteStore.CreateResult.Created -> ids += created.inviteId
                is FriendInviteStore.CreateResult.Refused -> {
                    // At the open cap: revoke one and continue.
                    val open = store.rows().first { it.state == FriendInviteStore.State.OPEN }
                    assertTrue(store.revoke(open.inviteId))
                }
            }
        }
        assertTrue("expected many creations", ids.size >= FriendInviteStore.MAX_OPEN_INVITES)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun persistedStateSurvivesAReopen() {
        val directory = folder.newFolder("friend-access")
        val first = FriendInviteStore(directory, clock = { nowMs })
        val created = first.create("Dave") as FriendInviteStore.CreateResult.Created
        first.submit(created.inviteId, created.tokenBase64Url, "Dave", validS(), validV(), "192.168.1.5")

        val second = FriendInviteStore(directory, clock = { nowMs })
        val row = second.rows().single()
        assertEquals(FriendInviteStore.State.SUBMITTED, row.state)
        assertEquals("Dave", row.username)
        // The held verifier did NOT survive the reopen (memory-only storage).
        assertEquals(null, second.markApproved(created.inviteId))
    }

    @Test
    fun rateLockRowsPersistAndExpireByClock() {
        val store = store()
        store.recordRateFailure("10.1.2.3", failures = 4, lockedUntilMs = 0L)
        assertEquals(0L, store.lockedUntilMs("10.1.2.3"))
        nowMs += 5_000
        store.recordRateFailure("10.1.2.3", failures = 0, lockedUntilMs = nowMs + 60_000)
        assertEquals(nowMs + 60_000, store.lockedUntilMs("10.1.2.3"))
        assertTrue(store.rateRows().single().locked)
        // Another ip is untouched.
        assertNotEquals(nowMs + 60_000, store.lockedUntilMs("10.9.9.9"))
    }

    @Test
    fun rateRowsAreCappedAndEvictOldestUnlockedFirst() {
        // layer 1, bounded persistence: a multi-source bad-token spray
        // must not grow the store without bound.
        nowMs = 1_000L
        val store = store()
        repeat(FriendInviteStore.MAX_RATE_ROWS + 8) { index ->
            store.recordRateFailure("10.0.0.$index", failures = 1, lockedUntilMs = 0L)
        }
        assertEquals(FriendInviteStore.MAX_RATE_ROWS, store.rateRows().size)
        assertEquals("the insertion-oldest unlocked rows were evicted", "10.0.0.8", store.rateRows().first().ip)

        // A LIVE lockout row is never evicted while unlocked rows exist.
        store.recordRateFailure("locked-peer", failures = 5, lockedUntilMs = 999_999L)
        repeat(FriendInviteStore.MAX_RATE_ROWS - 1) { index ->
            store.recordRateFailure("10.1.0.$index", failures = 1, lockedUntilMs = 0L)
        }
        val rows = store.rateRows()
        assertEquals(FriendInviteStore.MAX_RATE_ROWS, rows.size)
        assertTrue(
            "the live lockout row survives the eviction",
            rows.any { it.ip == "locked-peer" && it.locked },
        )
    }

    /**
     * writeLocked's temp-file suffix must stay platform-NEUTRAL: this store
     * is shared source (shared-sources.json) compiled by both builds, and it
     * ships on Android minSdk 26 — java.lang.ProcessHandle exists there only
     * from API 33 (a pid suffix crashed every API<=32 create/submit/rate
     * write with NoSuchMethodError), while android.os.Process.myPid() does
     * not exist on the desktop JVM. Pinned as a source contract because a
     * JVM test cannot reproduce an Android API-level linkage failure — the
     * same pattern as LanTunnelWiringContractTest over host-untestable glue.
     */
    @Test
    fun theTempFileSuffixStaysPlatformNeutral() {
        val candidates = listOf(
            File("src/main/java/com/pocketrealm/supervisor/FriendInviteStore.kt"),
            File("app/src/main/java/com/pocketrealm/supervisor/FriendInviteStore.kt"),
            File("android/app/src/main/java/com/pocketrealm/supervisor/FriendInviteStore.kt"),
            File("../android/app/src/main/java/com/pocketrealm/supervisor/FriendInviteStore.kt"),
        )
        val source = candidates.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: throw AssertionError("FriendInviteStore.kt not found from ${File(".").absolutePath}")
        assertFalse(
            "API 33+ on a minSdk 26 build: java.lang.ProcessHandle must not be referenced",
            "ProcessHandle" in source,
        )
        assertFalse(
            "absent from the desktop JVM build that compiles this same file",
            "android.os.Process" in source,
        )
    }
}

// ---------------------------------------------------------------------------
// the friend-access audit's bounded
// persistence — every refused submission appends a row, so a sustained
// bad-submission spray against the LAN-exposed port must not convert
// attacker traffic into unbounded host-disk growth (the same discipline
// the rate rows' MAX_RATE_ROWS applies), and the host UI's auditTail must
// never materialize a spray-grown file whole.
// ---------------------------------------------------------------------------

class FriendInviteStoreAuditBudgetTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var nowMs = 9_000_000L

    private fun store(maxRows: Int, maxBytes: Long): FriendInviteStore =
        FriendInviteStore(
            folder.newFolder("friend-$maxRows-$maxBytes"),
            clock = { nowMs },
            maxAuditRows = maxRows,
            maxAuditBytes = maxBytes,
        )

    @Test
    fun `the audit file rotates at the row cap under a refused-submission spray`() {
        val cap = 50
        // a byte budget the spray exceeds (~150 B per refused submission):
        // rotation triggers partway through, keeping the newest rows.
        val directory = folder.newFolder("audit-cap")
        val store = FriendInviteStore(
            directory,
            clock = { nowMs },
            maxAuditRows = cap,
            maxAuditBytes = 4_096L,
        )
        val created = (store.create("spray") as FriendInviteStore.CreateResult.Created)
        // The spray shape that the bounded persistence exists for: BAD-TOKEN
        // submissions from rotating sources (each refusal audits a row —
        // the same valid token would be the friend's read-only poll, which
        // deliberately does not audit).
        repeat(cap * 4) { attempt ->
            store.submit(
                inviteId = created.inviteId,
                token = "bad-token-$attempt",
                username = "spray$attempt",
                sHex = "ab".repeat(32),
                vHex = "cd".repeat(32),
                sourceIp = "10.9.0.$attempt",
            )
        }
        val auditFile = File(directory, FriendInviteStore.AUDIT_FILE_NAME)
        assertTrue("the audit file must exist", auditFile.isFile)
        val lineCount = auditFile.readLines().count { it.isNotBlank() }
        assertTrue(
            "the audit file must rotate at the cap: $lineCount lines > $cap",
            lineCount <= cap,
        )
        // the newest rows survive: the LAST spray attempt is still recorded
        assertTrue(
            "the newest row must survive rotation; tail=" +
                auditFile.readLines().takeLast(3),
            auditFile.readText().contains("10.9.0.${cap * 4 - 1}"),
        )
    }

    @Test
    fun `auditTail reads a spray-grown file without materializing it whole`() {
        val directory = folder.newFolder("audit-tail")
        val store = FriendInviteStore(directory, clock = { nowMs })
        val auditFile = File(directory, "friend-access-audit.jsonl")
        // a synthetic multi-MB body: ~20k rows of ~150 bytes
        val row = JSONObject()
            .put("at", 1L).put("action", "submit-refused")
            .put("username", "sprayed-name-000000")
            .put("sourceIp", "10.8.0.1")
            .put("outcome", "refused").toString()
        val bigBody = StringBuilder()
        repeat(20_000) { bigBody.append(row).append('\n') }
        auditFile.writeText(bigBody.toString(), Charsets.UTF_8)
        val tail = store.auditTail(limit = 100)
        assertTrue("the tail must be bounded", tail.size <= 100)
        assertEquals("the newest audit row is the last", "submit-refused", tail.last().optString("action"))
    }
}
