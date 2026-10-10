package com.pocketrealm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R1-1 (android-core review round 1, serious): account creation has TWO
 * seams and they must never diverge again on the per-flavor expansion
 * write. The verifier path (`createAccountVerifier`, the friend
 * provisioning route) used to skip the F1-10 addon leg entirely - the
 * native INSERT births the row at the seed's expansion default (0), so at
 * the W4.4 flip every provisioned friend on a tbc/wotlk host was silently
 * born classic. Both legs are pinned in ONE source contract: the exact
 * same expansion resolution and addon chaining must appear in
 * `createAccount` and `createAccountVerifier`, and the supervisor's
 * provision route must delegate here (never create accounts itself).
 */
class AccountExpansionWiringTest {

    private fun serviceSource(): String = locateSource(
        "android/app/src/main/java/com/pocketrealm/server/WorldRuntimeService.kt",
    )

    private fun supervisorSource(): String = locateSource(
        "android/app/src/main/java/com/pocketrealm/supervisor/DurableRuntimeSupervisor.kt",
    )

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

    @Test
    fun bothAccountCreationSeamsChainTheSameExpansionWrite() {
        val source = serviceSource()
        val create = methodBody(source, "override fun createAccount(username: String, password: String)")
        val verifier = methodBody(
            source,
            "override fun createAccountVerifier(",
        )
        for ((name, body) in listOf("createAccount" to create, "createAccountVerifier" to verifier)) {
            assertTrue(
                "$name: lost the F1-10 expansion resolution",
                "(bootLane\n" +
                    "                ?: worldLaneFlavor(ACCOUNT_EXPANSION_LANE)).accountExpansion" in body,
            )
            assertTrue(
                "$name: lost the addon write",
                "WorldNative.setAccountAddonNative(" in body,
            )
            // R1-arch-1 (review round 1, serious): the decision must be the
            // retry-aware pure function — the old `if (rc == 0)` guard
            // skipped the addon write on the designed HOST RETRY leg
            // (ACCOUNT_EXISTS with the row present from attempt 1), birthing
            // a silently classic friend on a tbc/wotlk host while the
            // accepted answer claimed the active flavor's expansion.
            if (name == "createAccount") {
                // the password lane: the password proof is the authorization
                assertTrue(
                    "$name: the addon decision must be the password-proof-" +
                        "gated shouldWriteExpansion",
                    "ServerRuntimeContract.shouldWriteExpansion(rc, accountExists) && identityProven" in body,
                )
            } else {
                // the verifier lane: the CONJUNCTIVE authorization decision
                // (R3-1: the round-2 OR-ed shape was a no-op gate)
                // the seam's call is formatted across lines: normalize
                // whitespace before matching the multi-line call
                assertTrue(
                    "$name: the addon decision must be the conjunctive " +
                        "verifierExpansionWrite",
                    Regex("\\s+").replace(body, " ").contains(
                        "ServerRuntimeContract.verifierExpansionWrite( " +
                            "rc, accountExists, allowExistingAccount,",
                    ),
                )
            }
            assertTrue(
                "$name: the seam must compute the accountExists input the " +
                    "decision consumes",
                "accountExists(username)" in body,
            )
            // Answer honesty: the addon result feeds the answer's rc, so a
            // failed addon write can never answer ok=true, and an accepted
            // answer always carries a real addon write (addonCode is never
            // "SKIPPED" when shouldWriteExpansion returned true, because the
            // decision predicate IS the accepted predicate).
            assertTrue(
                "$name: the addon result must feed the answer rc (a failed " +
                    "addon write fails the answer)",
                "addonRc ?: rc" in body,
            )
        }
        // The two legs must resolve the expansion through the IDENTICAL
        // expression - a per-leg literal is exactly the divergence that
        // birthed this finding.
        val resolution = "(bootLane\n" +
            "                ?: worldLaneFlavor(ACCOUNT_EXPANSION_LANE)).accountExpansion"
        fun occurrences(text: String, needle: String): Int =
            Regex(Regex.escape(needle)).findAll(text).count()
        assertEquals(
            "both seams must use the same expansion resolution expression",
            occurrences(create, resolution),
            occurrences(verifier, resolution),
        )
    }

    @Test
    fun verifierExpansionWriteTruthTableIsPinnedBehaviorally() {
        // R3-1/R3-2 (review round 3): the verifier lane's decision is
        // BEHAVIORALLY pinned — the round-2 source-string pins passed while
        // the seam's OR-ed expression kept the collision hole open. Truth
        // table over the pure function the seam consumes:
        // (0, _, _) = true: a fresh create always writes.
        for (exists in listOf(true, false)) {
            for (allow in listOf(true, false)) {
                assertTrue(
                    "rc=0 must write (exists=$exists, allow=$allow)",
                    ServerRuntimeContract.verifierExpansionWrite(0, exists, allow),
                )
            }
        }
        // (10, true, false) = FALSE: the collision refusal — the exact case
        // the round-2 OR-ed shape got wrong.
        assertTrue(
            !ServerRuntimeContract.verifierExpansionWrite(
                ServerRuntimeContract.ACCOUNT_EXISTS_CODE,
                accountExists = true,
                allowExistingAccount = false,
            ),
        )
        // (10, true, true) = true: the designed retry converges.
        assertTrue(
            ServerRuntimeContract.verifierExpansionWrite(
                ServerRuntimeContract.ACCOUNT_EXISTS_CODE,
                accountExists = true,
                allowExistingAccount = true,
            ),
        )
        // (10, false, *) = false: ACCOUNT_EXISTS without a verified row.
        for (allow in listOf(true, false)) {
            assertTrue(
                !ServerRuntimeContract.verifierExpansionWrite(
                    ServerRuntimeContract.ACCOUNT_EXISTS_CODE,
                    accountExists = false,
                    allowExistingAccount = allow,
                ),
            )
        }
        // every other rc = false.
        for (rc in intArrayOf(1, 5, 9, 11, 12)) {
            for (allow in listOf(true, false)) {
                assertTrue(
                    !ServerRuntimeContract.verifierExpansionWrite(rc, true, allow),
                )
            }
        }
    }

    @Test
    fun shouldWriteExpansionIsTheAcceptedShapeIncludingTheRetryLeg() {
        // R1-arch-1: the pure decision is the accountResult accepted shape
        // (rc == 0, or ACCOUNT_EXISTS with the row verified present).
        assertTrue("rc == 0 always writes", ServerRuntimeContract.shouldWriteExpansion(0, accountExists = false))
        assertTrue(
            "ACCOUNT_EXISTS with the row present is the designed HOST RETRY leg",
            ServerRuntimeContract.shouldWriteExpansion(10, accountExists = true),
        )
        assertFalse(
            "ACCOUNT_EXISTS without a row is an inconsistent native state - no write",
            ServerRuntimeContract.shouldWriteExpansion(10, accountExists = false),
        )
        for (rc in intArrayOf(1, 5, 9, 11, 12)) {
            assertFalse("failure rc $rc never writes", ServerRuntimeContract.shouldWriteExpansion(rc, accountExists = true))
        }
    }

    @Test
    fun theExpansionWriteBindsToTheBootLaneAndTheVerifierGatesTheRetry() {
        // R2-2 (review round 2): the expansion write consumes the BOOT lane
        // (pinned from the start leg's own snapshot) - a Home switch after
        // world start must never re-lane the expansion write.
        // R2-1 (review round 2, serious): the verifier seam gates the
        // ACCOUNT_EXISTS convergence behind the host's attempt-bookkeeping
        // authorization (allowExistingAccount); an unauthorized collision
        // answers ok=false with no addon write.
        val source = serviceSource()
        val verifier = methodBody(
            source,
            "override fun createAccountVerifier(",
        )
        assertTrue(
            "the start legs must pin the boot lane for the account seams",
            "bootLane = worldLaneFlavor(PreparedDataStore.SERVER_DATA_LANE, settingsSnapshot)" in source,
        )
        assertTrue(
            "the expansion write must consume the boot lane",
            "val expansion = (bootLane" in verifier,
        )
        assertTrue(
            "the ACCOUNT_EXISTS convergence must be gated by the host's " +
                "attempt-bookkeeping authorization (the signature carries the " +
                "parameter; the body consumes it in the decision)",
            "allowExistingAccount: Boolean" in source &&
                "rc, accountExists, allowExistingAccount," in verifier,
        )
        assertTrue(
            "an unauthorized ACCOUNT_EXISTS must answer ok=false (never the " +
                "accepted shape accountResult would compute)",
            ".put(\"ok\", accepted)" in verifier,
        )
    }

    @Test
    fun theExpansionLanesResolveThroughTheWorldProcessBinding() {
        // R1-arch-5/R2-2 (review round 1): the ActiveFlavor holder's
        // selection is :ui-process-local; :world never seeds it. The
        // expansion legs must resolve through the persisted-Settings form
        // (worldLaneFlavor), never the bare holder, and the overlay plan's
        // lane binding must be installed at :world start.
        val source = serviceSource()
        for (signature in listOf(
            "override fun createAccount(username: String, password: String)",
            "override fun createAccountVerifier(",
        )) {
            val body = methodBody(source, signature)
            assertFalse(
                "$signature: the bare in-memory holder resolve must not " +
                    "appear in a :world seam (it refuses at a multi-qualified " +
                    "flip even with a valid persisted pick)",
                "ActiveFlavor.resolve(" in body,
            )
        }
        assertTrue(
            "worldLaneFlavor must thread the persisted Home selection " +
                "(ActiveFlavorState over Settings.selectedFlavorOrNull)",
            "ActiveFlavorState(" in source &&
                "selectedFlavorOrNull()" in source,
        )
        assertTrue(
            "the overlay plan's lane binding must be installed at :world " +
                "onCreate (the overlay resolves in this process too)",
            "TimeframeOverlayPlan.installLaneResolver" in source,
        )
    }

    @Test
    fun theProvisionRouteDelegatesToTheSeamThatWritesTheExpansion() {
        val supervisor = supervisorSource()
        // The supervisor routes friend provisioning through the backend
        // interface (implemented by WorldRuntimeService) - it must never
        // create accounts natively itself, or it would bypass the
        // expansion write pinned above.
        assertTrue(
            "the supervisor must delegate provisioning (provisionAccountVerifier)",
            "provisionAccountVerifier" in supervisor,
        )
        assertFalse(
            "the supervisor must not create verifier accounts natively " +
                "(it would bypass the expansion write)",
            supervisor.contains("createAccountVerifierNative"),
        )
        assertFalse(
            "the supervisor must not write addons natively (it would " +
                "duplicate or bypass the seam's write)",
            supervisor.contains("setAccountAddonNative"),
        )
    }
    /**
     * R3-1 (native-server review round 3): an all-digit username passes
     * every token validator, but the core's `account set` handlers resolve
     * their argument through ExtractAccountId, whose NUMERIC branch
     * reinterprets a digit string as an ACCOUNT ID — the digits-named
     * guest was either rejected forever on an expansion provisioning lane
     * or received another account's expansion write on an id collision,
     * answering with a code wrong about the world state. The native ops
     * now resolve the id native-side and pass the NUMERIC id (pinned by
     * tests/test_native_roster_payload.py, the C++ contract's home). This
     * pin holds the SERVICE half of the same invariant: the provisioning
     * answer's `ok` is CONJUNCTIVE with the addon verdict whenever the
     * expansion write runs — so a failed or misrouted addon op can never
     * answer ok with a row created (the "row-created +
     * addonCode=ACCOUNT_REJECTED" shape is structurally impossible), and a
     * digits-named guest on a tbc/wotlk lane now converges: the addon
     * write lands on the resolved row and the answer is accepted.
     * (A live tbc boot lane is not drivable in JVM unit tests — the same
     * reason this seam's other invariants are source-pinned here.)
     */
    @Test
    fun provisioningAnswerIsNeverOkWithAFailedExpansionWrite() {
        val source = serviceSource()
        val create = methodBody(source, "override fun createAccount(username: String, password: String)")
        // the expansion write runs exactly when shouldWriteExpansion(rc, accountExists)
        assertTrue(
            "the addon write must stay gated on shouldWriteExpansion(rc, accountExists)",
            create.contains("if (ServerRuntimeContract.shouldWriteExpansion(rc, accountExists) && identityProven)"),
        )
        // the accepted verdict CONSUMES the addon verdict — never rc alone
        assertTrue(
            "the accepted leg must be conjunctive with the addon verdict " +
                "(addonRc == 0 && identityProven) when the expansion write runs",
            create.contains("addonRc == 0 && identityProven"),
        )
        // the answer surfaces the addon verdict by name, never "SKIPPED"
        // for a lane that owed the write
        assertTrue(
            "the answer code must map the addon verdict (errorName(addonRc ?: rc))",
            create.contains("ServerRuntimeContract.errorName(addonRc?.toLong() ?: rc.toLong())"),
        )
        assertTrue(
            "the answer must carry addonCode for the surface copy",
            create.contains("\"addonCode\""),
        )
    }
}
