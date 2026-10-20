package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.GatewayContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * the transport secret arrives on STDIN —
 * never argv (a 64-hex argv is Win32_Process-readable by any local
 * process for the endpoint's whole lifetime). Pins the pipe shape: the
 * flag reads one trimmed hex line; empty stdin is a loud BadArgs; passing
 * BOTH channels refuses; the E2E's `--secret <hex>` spelling still works.
 */
class GatewaySecretStdinTest {

    private val secret = "ab".repeat(32)

    private fun withStdin(payload: String?, block: () -> Unit) {
        val original = System.`in`
        try {
            System.setIn(
                if (payload == null) {
                    ByteArrayInputStream(ByteArray(0))
                } else {
                    ByteArrayInputStream((payload + "\n").toByteArray(Charsets.UTF_8))
                },
            )
            block()
        } finally {
            System.setIn(original)
        }
    }

    @Test
    fun `the stdin flag reads the trimmed hex line`() {
        withStdin(secret.uppercase()) {
            val parsed = Args.parse(
                arrayOf(GatewayContract.ARG_MODE, GatewayContract.MODE_HOST, GatewayContract.ARG_SECRET_STDIN),
            )
            // The identity is normalized to lowercase (the same rule the
            // argv spelling always applied).
            assertEquals(secret, parsed.secretHex)
        }
    }

    @Test
    fun `empty stdin under the stdin flag is a loud bad-args`() {
        withStdin(null) {
            val failure = assertThrows<BadArgsExit> {
                Args.parse(
                    arrayOf(GatewayContract.ARG_MODE, GatewayContract.MODE_HOST, GatewayContract.ARG_SECRET_STDIN),
                )
            }
            assertTrue(
                failure.message!!.contains(GatewayContract.ARG_SECRET_STDIN),
                "the message must name the stdin channel, never a bare NPE",
            )
        }
    }

    @Test
    fun `passing both secret channels refuses`() {
        val failure = assertThrows<BadArgsExit> {
            Args.parse(
                arrayOf(
                    GatewayContract.ARG_MODE, GatewayContract.MODE_HOST,
                    GatewayContract.ARG_SECRET_HEX, secret,
                    GatewayContract.ARG_SECRET_STDIN,
                ),
            )
        }
        assertTrue(
            failure.message!!.contains("ONE way"),
            "the both-channels refusal must be the explicit ONE-way message",
        )
    }

    @Test
    fun `the argv spelling still works for the direct E2E spawns`() {
        val parsed = Args.parse(
            arrayOf(GatewayContract.ARG_MODE, GatewayContract.MODE_HOST, GatewayContract.ARG_SECRET_HEX, secret),
        )
        assertEquals(secret, parsed.secretHex)
    }


    /** W6-N4 code-red round 6, R2-6-1's proposed rows: the charset gate
     * must refuse UNICODE digits — Char.isDigit() (the old check) is
     * Unicode-aware, and the UTF-8 STDIN leg (the production channel,
     * R3-3) is where the hole was live: a U+0663-class char rode the
     * gate and SecretKeys.fromHex would derive identity bytes from a
     * non-hex spelling. Both legs pinned: the stdin secret and --node. */
    @Test
    fun `a unicode-digit stdin secret refuses with the secret gate message`() {
        val unicodeDigit = "٣" // U+0663 ARABIC-INDIC DIGIT THREE: isDigit() == true
        val secret = "a".repeat(63) + unicodeDigit
        assertEquals(64, secret.length)
        val originalIn = System.`in`
        System.setIn(secret.byteInputStream(Charsets.UTF_8))
        try {
            val outcome = runCatching {
                Args.parse(
                    arrayOf(
                        GatewayContract.ARG_MODE, GatewayContract.MODE_JOIN,
                        GatewayContract.ARG_SECRET_STDIN,
                        GatewayContract.ARG_NODE_HEX, "b".repeat(64),
                        GatewayContract.ARG_FLAVOR, "wotlk",
                        GatewayContract.ARG_RELAY_URL, "https://usw1-1.relay.n0.iroh.link/",
                    ),
                )
            }
            System.setIn(originalIn)
            val failure = outcome.exceptionOrNull()
            assertTrue(
                failure is BadArgsExit,
                "a Unicode-digit secret must refuse as BadArgsExit, got $failure",
            )
            assertEquals(
                "--secret must be 64 hex characters (32 bytes)",
                failure.message,
                "the gate's own copy must be TRUE for its production channel",
            )
        } catch (t: Throwable) {
            System.setIn(originalIn)
            throw t
        }
    }

    @Test
    fun `a unicode-digit node refuses with the node gate message`() {
        val unicodeDigit = "٣"
        val node = "b".repeat(63) + unicodeDigit
        assertEquals(64, node.length)
        val originalIn = System.`in`
        System.setIn("a".repeat(64).byteInputStream(Charsets.UTF_8))
        try {
            val outcome = runCatching {
                Args.parse(
                    arrayOf(
                        GatewayContract.ARG_MODE, GatewayContract.MODE_JOIN,
                        GatewayContract.ARG_SECRET_STDIN,
                        GatewayContract.ARG_NODE_HEX, node,
                        GatewayContract.ARG_FLAVOR, "wotlk",
                        GatewayContract.ARG_RELAY_URL, "https://usw1-1.relay.n0.iroh.link/",
                    ),
                )
            }
            System.setIn(originalIn)
            val failure = outcome.exceptionOrNull()
            assertTrue(
                failure is BadArgsExit,
                "a Unicode-digit node must refuse as BadArgsExit, got $failure",
            )
            assertEquals(
                "--node must be 64 hex characters",
                failure.message,
            )
        } catch (t: Throwable) {
            System.setIn(originalIn)
            throw t
        }
    }

    @Test
    fun `an ascii hex secret still parses after the charset tightening`() {
        val secret = "a".repeat(63) + "F"
        val originalIn = System.`in`
        System.setIn(secret.byteInputStream(Charsets.UTF_8))
        try {
            val parsed = Args.parse(
                arrayOf(
                    GatewayContract.ARG_MODE, GatewayContract.MODE_JOIN,
                    GatewayContract.ARG_SECRET_STDIN,
                    GatewayContract.ARG_NODE_HEX, "b".repeat(64),
                    GatewayContract.ARG_FLAVOR, "wotlk",
                    GatewayContract.ARG_RELAY_URL, "https://usw1-1.relay.n0.iroh.link/",
                ),
            )
            // the gateway lowercases the secret (the canonical form)
            assertEquals(secret.lowercase(), parsed.secretHex)
        } catch (t: Throwable) {
            System.setIn(originalIn)
            throw t
        }
    }
}
