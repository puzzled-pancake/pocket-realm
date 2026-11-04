package com.pocketrealm.ui

import com.pocketrealm.server.FriendAccessClient

/**
 * The join-side friend-access flow (design-remote-accounts): submit
 * the invite-token request, then poll the read-only decision poll until the
 * host approves (the ONE response carrying the accountId), refuses, or the
 * poll budget ends. Pure JVM and transport-injected so the UI-independent
 * transitions are unit-testable; the Composable wires the real
 * [FriendAccessClient] transport and the account-save callback (the
 * friend's app saves the account for auto-login exactly like the host's
 * account card).
 */
fun interface FriendAccessTransport {
    suspend fun request(
        host: String,
        port: Int,
        inviteId: String,
        token: String,
        username: String,
        password: String,
    ): FriendAccessClient.Verdict
}

/** Adapts the real one-shot socket client to the flow's transport seam. */
class RealFriendAccessTransport(
    private val client: FriendAccessClient = FriendAccessClient(),
) : FriendAccessTransport {
    override suspend fun request(
        host: String,
        port: Int,
        inviteId: String,
        token: String,
        username: String,
        password: String,
    ): FriendAccessClient.Verdict =
        client.requestAccess(host, port, inviteId, token, username, password)
}

sealed class FriendRequestOutcome {
    /** The poll budget — the host's FULL 15-minute decision window — ended
     * without a decision: the request expired host-side when the window
     * closed. The UI says the checking stopped and what to do next, never
     * a present-tense "the host has not decided yet". */
    data object Submitted : FriendRequestOutcome()

    /** Approved: [accountId] is real and [onApproved] already ran. */
    data class Approved(val accountId: Long) : FriendRequestOutcome()

    /** The host refused (unknown/expired/burned/locked). */
    data object Refused : FriendRequestOutcome()

    /** The host was never reachable — a blocked port must never present as
     * "waiting for the host to click Approve" ( exposure invariant). */
    data class Unreachable(val reason: String) : FriendRequestOutcome()
}

/** 3 s between decision polls. */
internal const val DEFAULT_POLL_DELAY_MS = 3_000L

/**
 * The poll budget COVERS the host's whole 15-minute decision window
 * (300 × 3 s): the old 100-poll budget gave up ~10 minutes before the
 * host's window closed, so the app stopped checking while the friend's
 * screen still said "the host has not decided yet" — same present tense,
 * no word that the checking had stopped.
 */
internal const val DEFAULT_MAX_POLLS = 300

suspend fun requestFriendAccess(
    transport: FriendAccessTransport,
    host: String,
    port: Int,
    inviteId: String,
    token: String,
    username: String,
    password: String,
    onApproved: suspend (Long) -> Unit,
    /** Live wait-status updates (design-remote-accounts: a dropped host
     * must never present as "waiting for the host to click Approve" — the
     * first mid-wait unreachable surfaces here while polling continues,
     * the desktop wait loop's exact shape). */
    onWaitUpdate: (String) -> Unit = {},
    pollDelayMs: Long = DEFAULT_POLL_DELAY_MS,
    maxPolls: Int = DEFAULT_MAX_POLLS,
): FriendRequestOutcome =
    when (val verdict = transport.request(host, port, inviteId, token, username, password)) {
        is FriendAccessClient.Verdict.Provisioned -> {
            onApproved(verdict.accountId)
            FriendRequestOutcome.Approved(verdict.accountId)
        }
        is FriendAccessClient.Verdict.Refused -> FriendRequestOutcome.Refused
        is FriendAccessClient.Verdict.Unreachable -> FriendRequestOutcome.Unreachable(
            "Could not reach $host:$port (${verdict.reason}). Check the host's " +
                "address, that the realm is up, and that friend access is " +
                "enabled there.",
        )
        is FriendAccessClient.Verdict.Submitted -> {
            var unreachableStreak = 0
            repeat(maxPolls) {
                kotlinx.coroutines.delay(pollDelayMs)
                when (val poll = transport.request(host, port, inviteId, token, username, password)) {
                    is FriendAccessClient.Verdict.Provisioned -> {
                        onApproved(poll.accountId)
                        return FriendRequestOutcome.Approved(poll.accountId)
                    }
                    is FriendAccessClient.Verdict.Refused -> return FriendRequestOutcome.Refused
                    is FriendAccessClient.Verdict.Unreachable -> {
                        // A dropped host must never present as "waiting for
                        // the host to click Approve": surface the first
                        // mid-wait failure and keep retrying (desktop twin).
                        unreachableStreak += 1
                        if (unreachableStreak == 1) {
                            onWaitUpdate(
                                "The host became unreachable while you wait (they may " +
                                    "be saving or offline) — still checking every few " +
                                    "seconds. Cancel backs out.",
                            )
                        }
                    }
                    is FriendAccessClient.Verdict.Submitted -> {
                        unreachableStreak = 0
                        onWaitUpdate("Waiting for the host to approve you…")
                    }
                }
            }
            // Budget spent — and the budget now spans the host's whole
            // decision window: without a decision, the invite expired
            // host-side when the window closed.
            FriendRequestOutcome.Submitted
        }
    }

/**
 * source contract: plan rule 7 — PEER ADDRESSES ARE
 * NEVER DISPLAYED. The friend-request rows render the requesting ACCOUNT
 * name only; the requester's sourceIp stays in the host's audit trail
 * (the diagnostics lane's redaction rules govern it) and must never be
 * interpolated into user-facing copy on either platform.
 */
const val FRIEND_REQUEST_COPY_FORBIDS_SOURCE_IP = "sourceIp"


/**
 * the joiner-side refusal line. The service DELIBERATELY
 * makes refusals indistinguishable (unknown/expired/burned tokens and the
 * rate lock answer byte-identically — FriendProvisionProtocolTest pins
 * it), so the copy must not assert a cause the protocol cannot know, and
 * the rate lock makes "try again" user-harmful guidance. The line states
 * the one honest fact (refused, no reason given) and the stable remedy
 * (contact the host — only they can see the audit trail and lift a lock).
 */
fun joinerRefusalCopy(): String =
    "The host refused this request without a reason — the invite code may " +
        "be wrong, expired, or already used, and repeated attempts are " +
        "rate-limited. Contact the host for a fresh invite."
