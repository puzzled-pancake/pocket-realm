package com.pocketrealm.ui

import com.pocketrealm.server.FriendAccessClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The join-side friend-access flow (design-remote-accounts §3/§5): the
 * UI-independent transitions — submit, the read-only approval poll, the
 * refusal and unreachable verdicts — against a scripted transport.
 */
class FriendRequestFlowTest {
    private class ScriptedTransport(
        private var script: ArrayDeque<FriendAccessClient.Verdict>,
    ) : FriendAccessTransport {
        val calls = mutableListOf<String>()
        override suspend fun request(
            host: String,
            port: Int,
            inviteId: String,
            token: String,
            username: String,
            password: String,
        ): FriendAccessClient.Verdict {
            calls += "$inviteId@$host:$port"
            return if (script.isEmpty()) FriendAccessClient.Verdict.Submitted(inviteId) else script.removeFirst()
        }
    }

    @Test
    fun immediateApprovalSavesTheAccountWithoutPolling() = runTest {
        val saved = mutableListOf<Long>()
        val transport = ScriptedTransport(
            ArrayDeque(listOf(FriendAccessClient.Verdict.Provisioned("FRD-A", "Dave", 21L))),
        )
        val outcome = requestFriendAccess(
            transport, "192.168.1.5", 3725, "FRD-A", "tok", "Dave", "pw",
            onApproved = { saved += it },
        )
        assertTrue(outcome is FriendRequestOutcome.Approved)
        assertEquals(21L, (outcome as FriendRequestOutcome.Approved).accountId)
        assertEquals(listOf(21L), saved)
        assertEquals("exactly one request when approval answers the submit", 1, transport.calls.size)
    }

    @Test
    fun pendingThenApprovedPollsUntilTheAccountIdArrives() = runTest {
        val saved = mutableListOf<Long>()
        val transport = ScriptedTransport(
            ArrayDeque(listOf(
                FriendAccessClient.Verdict.Submitted("FRD-A"),
                FriendAccessClient.Verdict.Submitted("FRD-A"),
                FriendAccessClient.Verdict.Provisioned("FRD-A", "Dave", 7L),
            )),
        )
        val outcome = requestFriendAccess(
            transport, "192.168.1.5", 3725, "FRD-A", "tok", "Dave", "pw",
            onApproved = { saved += it },
            pollDelayMs = 1L,
        )
        assertTrue(outcome is FriendRequestOutcome.Approved)
        assertEquals(7L, (outcome as FriendRequestOutcome.Approved).accountId)
        assertEquals(listOf(7L), saved)
        assertEquals(3, transport.calls.size)
    }

    @Test
    fun aRefusalAtAnyLegIsTheRefusedOutcomeAndNeverSaves() = runTest {
        val saved = mutableListOf<Long>()
        val refusedUpFront = ScriptedTransport(ArrayDeque(listOf(FriendAccessClient.Verdict.Refused())))
        assertTrue(
            requestFriendAccess(
                refusedUpFront, "192.168.1.5", 3725, "FRD-A", "tok", "Dave", "pw",
                onApproved = { saved += it },
            ) is FriendRequestOutcome.Refused,
        )
        val refusedLate = ScriptedTransport(ArrayDeque(listOf(
            FriendAccessClient.Verdict.Submitted("FRD-A"),
            FriendAccessClient.Verdict.Refused(),
        )))
        assertTrue(
            requestFriendAccess(
                refusedLate, "192.168.1.5", 3725, "FRD-A", "tok", "Dave", "pw",
                onApproved = { saved += it },
                pollDelayMs = 1L,
            ) is FriendRequestOutcome.Refused,
        )
        assertTrue("no account is saved from a refused request", saved.isEmpty())
    }

    @Test
    fun anUnreachableSubmitNamesTheHostAndNeverPretendsToWait() = runTest {
        val transport = ScriptedTransport(
            ArrayDeque(listOf(FriendAccessClient.Verdict.Unreachable("connect timed out"))),
        )
        val outcome = requestFriendAccess(
            transport, "192.168.1.99", 3725, "FRD-A", "tok", "Dave", "pw",
            onApproved = {},
        )
        assertTrue(outcome is FriendRequestOutcome.Unreachable)
        assertTrue(
            "the error names the host:port (§6)",
            (outcome as FriendRequestOutcome.Unreachable).reason.contains("192.168.1.99:3725"),
        )
        assertTrue(
            "the unreachable copy carries the desktop twin's friend-access remedy",
            outcome.reason.contains("friend access is enabled there"),
        )
    }

    @Test
    fun aMidWaitHostDropSurfacesAndKeepsCheckingUntilApproval() = runTest {
        // §6: a dropped host must never present as "waiting for the host to
        // click Approve" — the first mid-wait unreachable surfaces through
        // the wait-update channel while polling continues.
        val saved = mutableListOf<Long>()
        val updates = mutableListOf<String>()
        val transport = ScriptedTransport(ArrayDeque(listOf(
            FriendAccessClient.Verdict.Submitted("FRD-A"),
            FriendAccessClient.Verdict.Unreachable("connect timed out"),
            FriendAccessClient.Verdict.Unreachable("connect timed out"),
            FriendAccessClient.Verdict.Submitted("FRD-A"),
            FriendAccessClient.Verdict.Provisioned("FRD-A", "Dave", 9L),
        )))
        val outcome = requestFriendAccess(
            transport, "192.168.1.5", 3725, "FRD-A", "tok", "Dave", "pw",
            onApproved = { saved += it },
            onWaitUpdate = { updates += it },
            pollDelayMs = 1L,
        )
        assertTrue(outcome is FriendRequestOutcome.Approved)
        assertEquals(9L, saved.single())
        assertTrue(
            "the drop surfaces while waiting: $updates",
            updates.any { it.contains("became unreachable") },
        )
        assertTrue(
            "a recovered poll clears the drop note: $updates",
            updates.any { it.contains("Waiting for the host to approve you") },
        )
    }

    @Test
    fun theDefaultPollBudgetCoversTheHostDecisionWindow() {
        // The budget must span the host's whole 15-minute decision window:
        // stopping ~10 minutes early left the friend's screen saying "the
        // host has not decided yet" long after the app stopped checking.
        assertTrue(
            "budget ${DEFAULT_MAX_POLLS * DEFAULT_POLL_DELAY_MS} ms < the 15-minute window",
            DEFAULT_MAX_POLLS * DEFAULT_POLL_DELAY_MS >= 15L * 60L * 1_000L,
        )
    }

    @Test
    fun aSpentPollBudgetReportsStillSubmittedWithoutSaving() = runTest {
        val saved = mutableListOf<Long>()
        val transport = ScriptedTransport(ArrayDeque()) // always Submitted
        val outcome = requestFriendAccess(
            transport, "192.168.1.5", 3725, "FRD-A", "tok", "Dave", "pw",
            onApproved = { saved += it },
            pollDelayMs = 1L,
            maxPolls = 4,
        )
        assertEquals(FriendRequestOutcome.Submitted, outcome)
        assertEquals("1 submit + 4 polls", 5, transport.calls.size)
        assertTrue(saved.isEmpty())
    }
}
