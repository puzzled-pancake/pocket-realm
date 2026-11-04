package com.pocketrealm.desktop.iroh

import com.pocketrealm.discovery.iroh.GatewayContract
import com.pocketrealm.discovery.iroh.SignupProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** The desktop side of the self sign-up stdio channel: a host gateway's
 * request line reaches the app's handler and the reply goes back on the
 * child's stdin; a join-side request is written to stdin and its reply
 * line is matched by id. */
class IrohSignupChannelTest {

    private class Captured : ByteArrayOutputStream() {
        @Synchronized fun text(): String = toString(Charsets.UTF_8)
    }

    /** A scripted gateway child: the test writes its stdout, reads its stdin. */
    private class FakeChild : Process() {
        val stdout = PipedOutputStream()
        private val stdoutIn = PipedInputStream(stdout, 64 * 1024)
        val stdin = Captured()
        @Volatile private var alive = true
        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdoutIn
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun waitFor(): Int = 0
        override fun exitValue(): Int = 0
        override fun isAlive(): Boolean = alive
        override fun destroy() {
            alive = false
            runCatching { stdout.close() }
        }
        fun emit(line: String) {
            stdout.write((line + "\n").toByteArray(Charsets.UTF_8))
            stdout.flush()
        }
    }

    private val request = SignupProtocol.Request("Wanderer", "1a".repeat(32), "2b".repeat(32))

    private fun awaitText(child: FakeChild, needle: String): String {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val text = child.stdin.text()
            if (text.contains(needle)) return text
            Thread.sleep(20)
        }
        return child.stdin.text()
    }

    @Test
    fun aHostRequestLineReachesTheHandlerAndTheReplyGoesBackOnStdin() {
        val manager = IrohGatewayManager(
            jarOverride = java.io.File("no/such/gateway.jar"),
            javaOverride = IrohGatewayManager.DEFAULT_JAVA_21,
        )
        val child = FakeChild()
        val captured = ArrayList<List<String>>()
        manager.processLauncher = { args, _ ->
            captured += args
            GatewayProcess(child, channelOut = child.stdin)
        }
        val seen = ArrayList<SignupProtocol.Request>()
        manager.hostSignupHandler = { incoming ->
            seen += incoming
            SignupProtocol.Reply(true, SignupProtocol.CREATED, "created")
        }
        try {
            manager.ensureHost("cd".repeat(32))
            assertTrue(captured.single().contains(GatewayContract.ARG_SIGNUP_CHANNEL))
            child.emit(
                SignupProtocol.encodeChannelLine(
                    GatewayContract.SIGNUP_REQUEST_LINE_PREFIX, 41L, SignupProtocol.encodeRequest(request),
                ),
            )
            val text = awaitText(child, GatewayContract.SIGNUP_REPLY_LINE_PREFIX)
            val line = text.lines().first { it.startsWith(GatewayContract.SIGNUP_REPLY_LINE_PREFIX) }
            val (id, frame) = SignupProtocol.decodeChannelLine(GatewayContract.SIGNUP_REPLY_LINE_PREFIX, line)!!
            assertEquals(41L, id)
            assertEquals(SignupProtocol.CREATED, SignupProtocol.decodeReply(frame)!!.code)
            assertEquals(listOf(request), seen)
        } finally {
            manager.stopHost()
        }
    }

    @Test
    fun withoutAHandlerTheHostAnswersSignupClosed() {
        val manager = IrohGatewayManager(
            jarOverride = java.io.File("no/such/gateway.jar"),
            javaOverride = IrohGatewayManager.DEFAULT_JAVA_21,
        )
        val child = FakeChild()
        manager.processLauncher = { _, _ -> GatewayProcess(child, channelOut = child.stdin) }
        try {
            manager.ensureHost("cd".repeat(32))
            child.emit(
                SignupProtocol.encodeChannelLine(
                    GatewayContract.SIGNUP_REQUEST_LINE_PREFIX, 1L, SignupProtocol.encodeRequest(request),
                ),
            )
            val text = awaitText(child, GatewayContract.SIGNUP_REPLY_LINE_PREFIX)
            assertTrue(text, text.contains(SignupProtocol.SIGNUP_CLOSED))
        } finally {
            manager.stopHost()
        }
    }

    @Test
    fun aJoinRequestIsWrittenToStdinAndItsReplyIsMatchedById() {
        val child = FakeChild()
        val process = GatewayProcess(child, channelOut = child.stdin)
        val answer = Thread {
            val text = awaitText(child, GatewayContract.SIGNUP_REQUEST_LINE_PREFIX)
            val line = text.lines().first { it.startsWith(GatewayContract.SIGNUP_REQUEST_LINE_PREFIX) }
            val (id, frame) = SignupProtocol.decodeChannelLine(GatewayContract.SIGNUP_REQUEST_LINE_PREFIX, line)!!
            assertEquals(request, SignupProtocol.decodeRequest(frame))
            // An unrelated id first, then the real answer.
            child.emit(
                SignupProtocol.encodeChannelLine(
                    GatewayContract.SIGNUP_REPLY_LINE_PREFIX, id + 100,
                    SignupProtocol.encodeReply(SignupProtocol.Reply(false, SignupProtocol.FAILED, "wrong")),
                ),
            )
            child.emit(
                SignupProtocol.encodeChannelLine(
                    GatewayContract.SIGNUP_REPLY_LINE_PREFIX, id,
                    SignupProtocol.encodeReply(SignupProtocol.Reply(false, SignupProtocol.NAME_TAKEN, "taken")),
                ),
            )
        }.apply { start() }
        val frame = process.requestSignup(SignupProtocol.encodeRequest(request), 10_000L)
        answer.join()
        assertNotNull(frame)
        assertEquals(SignupProtocol.NAME_TAKEN, SignupProtocol.decodeReply(frame!!)!!.code)
        process.stop()
    }
}
