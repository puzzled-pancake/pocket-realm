package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.GatewayContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W6-N3 code-red round 4 (R3-3): the transport secret arrives on STDIN —
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
}
