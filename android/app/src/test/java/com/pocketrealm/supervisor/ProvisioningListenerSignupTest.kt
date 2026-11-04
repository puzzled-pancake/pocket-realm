package com.pocketrealm.supervisor

import com.pocketrealm.discovery.iroh.SignupProtocol
import com.pocketrealm.server.FriendAccessClient
import com.pocketrealm.server.FriendProvisionProtocol
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The D7 LAN sign-up lane over the friend-access port: the kind=signup
 * frame reaches the host's lane (never the invite oracle), the verdicts
 * are the friend-visible refusals (closed, wrong password, cap), the
 * correct password's proof is accepted, and every refusal feeds the same
 * per-source rate limiter as the invite lane (5 failures lock the source).
 */
class ProvisioningListenerSignupTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val sHex = "aa".repeat(32)
    private val vHex = "bb".repeat(32)

    /** A listener on an ephemeral port with the given lane; the port it
     * actually bound is the client's target. */
    private class Live(
        val listener: ProvisioningListener,
        val port: Int,
    )

    private fun live(
        gamePassword: String? = null,
        signupOpen: Boolean = true,
        cap: Int = 0,
        signedUp: List<String> = emptyList(),
        laneWired: Boolean = true,
    ): Live {
        val store = FriendInviteStore(folder.newFolder())
        val listener = ProvisioningListener(
            store = store,
            rateLimiter = ProvisionRateLimiter(store),
            bindAddress = "127.0.0.1",
            port = 0,
            signupLane = if (!laneWired) {
                null
            } else {
                { request ->
                    if (!signupOpen) {
                        SignupProtocol.Reply(false, SignupProtocol.SIGNUP_CLOSED, "This realm is not taking sign-ups right now.")
                    } else if (gamePassword != null &&
                        !com.pocketrealm.discovery.iroh.JoinPasscode.matches(
                            FriendProvisionProtocol.lanSignupPass(gamePassword),
                            request.pass,
                        )
                    ) {
                        SignupProtocol.PASSWORD_WRONG_REPLY
                    } else {
                        SignupProtocol.gate(
                            open = true,
                            cap,
                            signedUp,
                            SignupProtocol.Request(request.username, request.sHex, request.vHex),
                        ) ?: SignupProtocol.Reply(true, SignupProtocol.CREATED, "Account ${request.username} created — log in with it.")
                    }
                }
            },
        )
        val state = listener.start()
        assertTrue(state is ProvisioningListener.ListenerState.Listening)
        return Live(listener, (state as ProvisioningListener.ListenerState.Listening).port)
    }

    private fun ask(
        port: Int,
        username: String = "newfriend",
        accountPassword: String = "sesame123",
        gamePassword: String? = null,
    ): FriendAccessClient.SignupOutcome =
        FriendAccessClient(connectTimeoutMs = 2_000, readTimeoutMs = 2_000).requestSignup(
            host = "127.0.0.1",
            port = port,
            username = username,
            password = accountPassword,
            gamePassword = gamePassword,
        )

    @Test
    fun signupRoundTripCarriesTheCreatedVerdict() {
        val live = live()
        val outcome = ask(live.port)
        val reply = (outcome as FriendAccessClient.SignupOutcome.Answered).reply
        assertEquals(SignupProtocol.CREATED, reply.code)
        assertTrue(reply.ok)
        live.listener.stop()
    }

    @Test
    fun theProofBindsTheGamePasswordWrongThenRight() {
        val live = live(gamePassword = "opensesame")
        val wrong = ask(live.port, gamePassword = "not-the-password")
        assertEquals(
            SignupProtocol.PASSWORD_WRONG,
            (wrong as FriendAccessClient.SignupOutcome.Answered).reply.code,
        )
        assertEquals(
            "That password isn't right.",
            (wrong as FriendAccessClient.SignupOutcome.Answered).reply.detail,
        )
        val right = ask(live.port, gamePassword = "opensesame")
        assertEquals(
            SignupProtocol.CREATED,
            (right as FriendAccessClient.SignupOutcome.Answered).reply.code,
        )
        live.listener.stop()
    }

    @Test
    fun closedRefusalsAreDistinctFromTheInviteOracle() {
        val closed = live(signupOpen = false)
        assertEquals(
            SignupProtocol.SIGNUP_CLOSED,
            ((ask(closed.port)) as FriendAccessClient.SignupOutcome.Answered).reply.code,
        )
        closed.listener.stop()

        // A host that never wired the lane answers the same closed refusal.
        val unwired = live(laneWired = false)
        assertEquals(
            SignupProtocol.SIGNUP_CLOSED,
            ((ask(unwired.port)) as FriendAccessClient.SignupOutcome.Answered).reply.code,
        )
        unwired.listener.stop()
    }

    @Test
    fun theSharedCapGateRefusesAtTheLimit() {
        val capped = live(cap = 1, signedUp = listOf("firstfriend"))
        assertEquals(
            SignupProtocol.CAP_REACHED,
            ((ask(capped.port)) as FriendAccessClient.SignupOutcome.Answered).reply.code,
        )
        capped.listener.stop()
    }

    @Test
    fun refusalsFeedThePerSourceRateLimiter() {
        val live = live(gamePassword = "opensesame")
        repeat(ProvisionRateLimiter.MAX_FAILURES) {
            val wrong = ask(live.port, username = "friend$it", gamePassword = "wrong")
            assertEquals(
                SignupProtocol.PASSWORD_WRONG,
                (wrong as FriendAccessClient.SignupOutcome.Answered).reply.code,
            )
        }
        // The source is now locked: the NEXT connection gets the invite
        // lane's identical refusal blob (the lockout precedes the decode),
        // which the client maps to the shared rate-limit sentence.
        val locked = ask(live.port, gamePassword = "opensesame")
        val reply = (locked as FriendAccessClient.SignupOutcome.Answered).reply
        assertEquals(SignupProtocol.RATE_LIMITED, reply.code)
        assertEquals(false, reply.ok)
        live.listener.stop()
    }

    @Test
    fun malformedSignupFramesFallToTheInviteOracle() {
        val live = live()
        // A kind=signup frame with a bad verifier must NOT reach the lane:
        // it is malformed, and the invite lane's refusal oracle answers.
        val store = FriendInviteStore(folder.newFolder())
        val listener = ProvisioningListener(
            store = store,
            rateLimiter = ProvisionRateLimiter(store),
            bindAddress = "127.0.0.1",
            port = 0,
            signupLane = { SignupProtocol.Reply(true, SignupProtocol.CREATED, "created") },
        )
        val state = listener.start() as ProvisioningListener.ListenerState.Listening
        java.net.Socket("127.0.0.1", state.port).use { socket ->
            socket.getOutputStream().apply {
                write(
                    FriendProvisionProtocol.frame(
                        org.json.JSONObject()
                            .put("schema", 1)
                            .put("kind", "signup")
                            .put("username", "someone")
                            .put("sHex", "not-hex")
                            .put("vHex", "not-hex")
                            .put("pass", "x")
                            .toString(),
                    ),
                )
                flush()
            }
            val payload = FriendProvisionProtocol.readFrame(
                socket.getInputStream(),
                FriendProvisionProtocol.MAX_RESPONSE_BYTES,
            )
            assertEquals(FriendProvisionProtocol.REFUSED_RESPONSE, String(payload!!, Charsets.UTF_8))
        }
        listener.stop()
        live.listener.stop()
    }
}
