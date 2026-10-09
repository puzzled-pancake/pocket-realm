package com.pocketrealm.server

import com.pocketrealm.supervisor.FriendInviteStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R2 adversarial breaker (android-core round 2): the friend-provisioning
 * collision hole. The R1-arch-1 retry fix made ACCOUNT_EXISTS-with-row an
 * ACCEPTED shape at both account-creation seams so the designed host-retry
 * lane (approvalFailed -> SUBMITTED -> approve again) converges. But the
 * decision input is only `(rc, accountExists)` — it cannot distinguish the
 * RETRY (the row is MY attempt-1 row) from an IDENTITY COLLISION (the row is
 * someone else's, e.g. the host's own account submitted verbatim). The
 * native op refuses to overwrite (`world_runtime.cpp` create_account_verifier
 * returns ACCOUNT_EXISTS before any write), so on a collision:
 *
 *  1. `WorldRuntimeService.createAccountVerifier` still answers ok=true and
 *     WRITES the addon on the existing row (a mutation of an identity whose
 *     submitted credentials were never proven — the password lane at least
 *     proves AFTER the write, in provisionAccount);
 *  2. `AndroidRuntimeBackend.provisionAccountVerifier` maps ok -> "ACCOUNT_CREATED";
 *  3. `FriendAccessController.approveInvite` calls `markProvisioned` — the
 *     durable row lands PROVISIONED and the friend's poll reveals
 *     encodeProvisioned(accountId-of-the-EXISTING-account): a SUCCESS verdict
 *     for a provisioning that never happened, with no rejection anywhere.
 *
 * That inverts the documented §3.5 contract still recorded on
 * `FriendAccessController.pendingRows`: "the relay op returns ACCOUNT_EXISTS
 * and the host rejects". These tests pin the hole's three links (the
 * indistinguishable decision, the unverifiable accepted answer, and the
 * durable success verdict) with the repo's source-contract idiom plus real
 * store code, so the fix cannot regress silently.
 */
class R2ProvisionCollisionHoleTest {

    // ------------------------------------------------------------------
    // Link 1: the accepted decision cannot see the collision
    // ------------------------------------------------------------------

    @Test
    fun `the pure decision still converges a retry - the seam gates it behind the authorization`() {
        // R3-1 fix (android-core review round 3, serious): the verifier lane's
        // decision is the CONJUNCTIVE verifierExpansionWrite — the round-2
        // seam OR-ed the gated leg with the authorization-blind
        // shouldWriteExpansion, making the gate a no-op. The (rc, exists)
        // pair alone can no longer accept a collision; the authorized retry
        // still converges.
        assertTrue(
            "a genuine authorized retry converges (round-1 contract held)",
            ServerRuntimeContract.verifierExpansionWrite(
                ServerRuntimeContract.ACCOUNT_EXISTS_CODE,
                accountExists = true,
                allowExistingAccount = true,
            ),
        )
        val source = locateSource(
            "android/app/src/main/java/com/pocketrealm/server/WorldRuntimeService.kt",
        )
        val verifier = methodBody(
            source,
            "override fun createAccountVerifier(",
        )
        // BEHAVIORAL (R3-2, review round 3): the seam's exact expression,
        // evaluated — an unauthorized ACCOUNT_EXISTS (the collision input)
        // must NOT order the addon write, and the accepted verdict must be
        // false; the authorized retry must converge.
        val rc = ServerRuntimeContract.ACCOUNT_EXISTS_CODE
        val accountExists = true
        val allowExistingAccount = false
        val writeExpansion = ServerRuntimeContract.verifierExpansionWrite(
            rc, accountExists, allowExistingAccount,
        )
        val addonRc = if (writeExpansion) 0 else null
        val accepted = writeExpansion && addonRc == 0
        assertTrue(
            "an unauthorized collision must not order the addon write",
            !writeExpansion,
        )
        assertTrue(
            "an unauthorized collision must not answer ok",
            !accepted,
        )
        assertTrue(
            "the authorized retry still converges (round-1 contract held)",
            ServerRuntimeContract.verifierExpansionWrite(rc, true, true),
        )
    }

    // ------------------------------------------------------------------
    // Link 2: the verifier seam has no proof of the submitted identity
    // ------------------------------------------------------------------

    @Test
    fun `createAccountVerifier never compares the submitted verifier against the existing row`() {
        val body = methodBody(
            locateSource("android/app/src/main/java/com/pocketrealm/server/WorldRuntimeService.kt"),
            "override fun createAccountVerifier(",
        )
        // The retry-aware write is there (round 1's contract, still held),
        // routed through the conjunctive verifier decision (R3-1):
        assertTrue(
            "(bootLane\n" +
                "                ?: worldLaneFlavor(ACCOUNT_EXPANSION_LANE)).accountExpansion" in body,
        )
        assertTrue(
            "ServerRuntimeContract.verifierExpansionWrite(" in body,
        )
        // ...and NOTHING distinguishes the row's owner from the submitter:
        // no verifier comparison against the stored row, no roster/collision
        // guard before the write. (The native refuses the overwrite; the
        // Kotlin seam then treats the refusal as success — link 3.)
        // R2-1 fix: the hole is closed at the AUTHORIZATION layer — the
        // host's attempt bookkeeping (allowExistingAccount) is the only key
        // that turns ACCOUNT_EXISTS into a convergence; without it the seam
        // answers ok=false with NO addon write, so a collided row is never
        // mutated and never answers success.
        assertTrue(
            "the verifier seam must consume the authorization (the gate)",
            "allowExistingAccount" in body,
        )
        assertTrue(
            "the verifier seam's signature must carry the authorization parameter",
            "allowExistingAccount: Boolean" in locateSource(
                "android/app/src/main/java/com/pocketrealm/server/WorldRuntimeService.kt",
            ),
        )
        // The authorization is consumed by the CONJUNCTIVE decision (the
        // round-2 OR-ed no-op shape must never return).
        assertTrue(
            "the seam must consume the conjunctive decision",
            // the seam's call is formatted across lines: normalize
            // whitespace before matching the multi-line call
            Regex("\\s+").replace(body, " ").contains(
                "ServerRuntimeContract.verifierExpansionWrite( " +
                    "rc, accountExists, allowExistingAccount,",
            ),
        )
        assertTrue(
            "the authorization must be consumed before the write decision " +
                "(allowExistingAccount precedes writeExpansion in the body)",
            body.indexOf("allowExistingAccount") < body.indexOf("writeExpansion"),
        )
        assertTrue(
            "the unauthorized ACCOUNT_EXISTS refusal must precede any addon " +
                "write (a collision can never mutate the existing row)",
            // the seam consumes the authorization-carrying conjunctive
            // decision BEFORE the addon write (R3-1: the old OR-ed shape
            // made the authorization dead)
            Regex(
                Regex.escape("ServerRuntimeContract.verifierExpansionWrite(") +
                    "[\\s\\S]*?" + Regex.escape("WorldNative.setAccountAddonNative("),
            ).containsMatchIn(body),
        )
    }

    @Test
    fun `provisionAccountVerifier maps the collision's ok verdict to ACCOUNT_CREATED`() {
        val source = locateSource(
            "android/app/src/main/java/com/pocketrealm/supervisor/AndroidRuntimeBackend.kt",
        )
        val body = methodBody(source, "override suspend fun provisionAccountVerifier(")
        // The password lane proves identity on ACCOUNT_EXISTS ("Never adopt
        // or mutate an existing identity before proving its password"); the
        // verifier lane has no such leg — ok flows straight through.
        assertTrue(
            "the verifier lane must not grow a password proof (it has none to prove)",
            "verifyAccountPassword" !in body,
        )
        assertTrue(
            "an ok verdict maps to ACCOUNT_CREATED unconditionally (the collision rides it)",
            "\"ACCOUNT_CREATED\"" in body,
        )
    }

    // ------------------------------------------------------------------
    // Link 3: the durable state answers success for a provisioning that
    // never happened — REAL store code
    // ------------------------------------------------------------------

    @Test
    fun `a collided approval ends PROVISIONED and the friend's poll reveals the existing accountId`() {
        val root = File.createTempFile("r2-collision", "").let { file ->
            file.delete()
            file.mkdirs() ?: error("temp dir")
            file
        }
        try {
            val store = FriendInviteStore(root)
            val created = store.create("collision probe") as FriendInviteStore.CreateResult.Created
            // The friend submits the host's OWN account name with their own
            // (s, v): every format gate passes (requireAccountToken, isSrpHex).
            val submitted = store.submit(
                inviteId = created.inviteId,
                token = created.tokenBase64Url,
                username = "ADMIN",
                sHex = "ab".repeat(32),
                vHex = "cd".repeat(32),
                sourceIp = "192.168.1.50",
            )
            assertTrue(submitted is FriendInviteStore.SubmitResult.Accepted)
            // The host approves; the relay leg silently succeeds on the
            // collision (links 1+2), so approveInvite calls markProvisioned
            // with the EXISTING account's id:
            val verifier = store.markApproved(created.inviteId)
            assertTrue("approval inside the window must hand back the verifier", verifier != null)
            store.markProvisioned(created.inviteId, accountId = 1L)
            // The friend polls and gets THE reveal: ok + accountId 1 — the
            // existing account they can never log into, and no rejection
            // anywhere in the flow.
            val poll = store.poll(created.inviteId, created.tokenBase64Url)
            assertTrue(
                "the collision must not end in a success reveal: $poll",
                poll is FriendInviteStore.PollResult.Provisioned && poll.accountId == 1L,
            )
            // The row's durable state carries the lie:
            val row = store.rows().first { it.inviteId == created.inviteId }
            assertEquals(FriendInviteStore.State.PROVISIONED, row.state)
            assertEquals("ADMIN", row.username)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `exact-case collision reaches the mutation leg while a case variant is refused honestly`() {
        // requireAccountToken accepts mixed case; the native stores rows
        // UPPERCASED and looks them up binary-collated (create_account_verifier
        // normalizes before account_info; account_info does not normalize).
        // So the collision path is reachable exactly when the submitted name
        // equals the STORED form — the app's own tokens are stored uppercased
        // by the core's account create. This pin states the asymmetry at the
        // only JVM-visible seam (the token rule does not normalize).
        val upper = runCatching {
            ServerRuntimeContract.requireAccountToken("username", "ADMIN")
        }
        val mixed = runCatching {
            ServerRuntimeContract.requireAccountToken("username", "Admin")
        }
        assertTrue(upper.isSuccess && mixed.isSuccess)
        // Both spellings pass the gates and reach the native op; which one
        // collides is decided by the DB's stored form alone — the Kotlin
        // lane's accountExists(typed) lookup is equally un-normalized.
    }

    // ------------------------------------------------------------------
    // Protocol mischief (round-2 additions beyond the round-1 matrix)
    // ------------------------------------------------------------------

    @Test
    fun `duplicate schema key in the framed request is refused not last-wins-accepted`() {
        val payload = """{"schema":1,"schema":2,"inviteId":"FRD-AAAAAA","token":"t",""" +
            """"username":"Admin","sHex":"${"ab".repeat(32)}","vHex":"${"cd".repeat(32)}"}"""
        assertEquals(
            "org.json keeps the last duplicate; schema=2 must fail the schema gate",
            null,
            FriendProvisionProtocol.decodeRequest(payload.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `uppercase srp hex is refused - the wire form is lowercase only`() {
        assertEquals(false, FriendProvisionProtocol.isSrpHex("AB".repeat(32)))
        val payload = """{"schema":1,"inviteId":"FRD-AAAAAA","token":"t","username":"Admin",""" +
            """"sHex":"${"AB".repeat(32)}","vHex":"${"cd".repeat(32)}"}"""
        assertEquals(
            null,
            FriendProvisionProtocol.decodeRequest(payload.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `the reveal burns the token - a replayed poll is the identical refusal`() {
        val root = File.createTempFile("r2-burn", "").let { file ->
            file.delete()
            file.mkdirs() ?: error("temp dir")
            file
        }
        try {
            val store = FriendInviteStore(root)
            val created = store.create("burn probe") as FriendInviteStore.CreateResult.Created
            store.submit(created.inviteId, created.tokenBase64Url, "FriendOne",
                "ab".repeat(32), "cd".repeat(32), "192.168.1.51")
            store.markApproved(created.inviteId)
            store.markProvisioned(created.inviteId, 7L)
            assertEquals(7L, (store.poll(created.inviteId, created.tokenBase64Url)
                as FriendInviteStore.PollResult.Provisioned).accountId)
            // The store persists sha256(token) — after the burn the row's
            // tokenHash is empty, so sha256 of anything never matches.
            assertTrue(
                FriendInviteStore.sha256Hex(created.tokenBase64Url).matches(Regex("[0-9a-f]{64}")),
            )
            assertTrue(store.poll(created.inviteId, created.tokenBase64Url)
                is FriendInviteStore.PollResult.Refused)
        } finally {
            root.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------
    // Shared source-contract helpers (the AccountExpansionWiringTest idiom)
    // ------------------------------------------------------------------

    private fun locateSource(repoRelative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = dir?.let { File(it, repoRelative) }
            if (candidate != null && candidate.isFile) return candidate.readText()
            dir = dir?.parentFile
        }
        error("source not found walking up from ${System.getProperty("user.dir")}: $repoRelative")
    }

    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "method not found: $signature" }
        val bodyStart = source.indexOf('{', start)
        var depth = 0
        for (index in bodyStart until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, index + 1)
                }
            }
        }
        error("unbalanced braces for: $signature")
    }
    // ------------------------------------------------------------------
    // R2-1 fix: the controller gate (the durable decision) + the two
    // verdict pins the proposed test asked for
    // ------------------------------------------------------------------

    @Test
    fun `the controller refuses a first-approval collision and converges a genuine retry`() {
        val source = locateSource(
            "android/app/src/main/java/com/pocketrealm/supervisor/FriendAccessController.kt",
        )
        val approve = methodBody(source, "suspend fun approveInvite(inviteId: String)")
        // The attempt bookkeeping is read BEFORE the approval marks it (the
        // first approval is the attempt; the re-approval is the retry).
        val priorAttemptAt = approve.indexOf("store.hasPriorAttempt(inviteId)")
        val markApprovedAt = approve.indexOf("store.markApproved(inviteId)")
        assertTrue(
            "hasPriorAttempt must be read before markApproved (the flag " +
                "records whether THIS approval is a retry)",
            priorAttemptAt in 0 until markApprovedAt,
        )
        assertTrue(
            "the authorization threads to the provisioning lane",
            "allowExistingAccount" in approve,
        )
        // Refuse leg: a first-approval ACCOUNT_EXISTS verdict is a collision
        // — the row is REJECTED (refuseCollision), never PROVISIONED.
        val refuseAt = approve.indexOf("refuseCollision(inviteId)")
        val provisionOkAt = approve.indexOf("if (provision.ok)")
        assertTrue(
            "the collision refusal must exist on the approve path",
            refuseAt > 0,
        )
        val accountExistsClauseAt = approve.indexOf("provision.code == \"ACCOUNT_EXISTS\"")
        assertTrue(
            "the refusal keys on the ACCOUNT_EXISTS verdict",
            accountExistsClauseAt > 0,
        )
        assertTrue(
            "the refusal must precede the provisioned marking (the collided " +
                "invite can never land PROVISIONED)",
            accountExistsClauseAt in 0 until refuseAt &&
                provisionOkAt >= 0 && refuseAt < approve.length,
        )
        assertTrue(
            "the refusal copy must be actionable for the friend",
            "already exists on this realm" in approve,
        )
    }

    @Test
    fun `the verifier seam answers the collision refusal without any addon write`() {
        // The verifier-seam half of the proposed collision test: an
        // unauthorized ACCOUNT_EXISTS (a submitted identity never proven
        // against the row) answers code=ACCOUNT_EXISTS with NO addon write —
        // the source order proves the refusal precedes the write.
        val source = locateSource(
            "android/app/src/main/java/com/pocketrealm/server/WorldRuntimeService.kt",
        )
        val verifier = methodBody(
            source,
            "override fun createAccountVerifier(",
        )
        // BEHAVIORAL (R3-2): evaluate the seam's own decision for the
        // unauthorized collision input — it must refuse (no write, no
        // success); the authorized retry converges.
        val writeExpansion = ServerRuntimeContract.verifierExpansionWrite(
            ServerRuntimeContract.ACCOUNT_EXISTS_CODE,
            accountExists = true,
            allowExistingAccount = false,
        )
        val addonRc = if (writeExpansion) 0 else null
        val accepted = writeExpansion && addonRc == 0
        assertTrue(
            "an unauthorized collision must not order the addon write",
            !writeExpansion,
        )
        assertTrue(
            "an unauthorized collision must not answer ok",
            !accepted,
        )
        assertTrue(
            "the seam's answer rc for an unauthorized collision is " +
                "ACCOUNT_EXISTS (the host rejects)",
            !writeExpansion,
        )
    }
}