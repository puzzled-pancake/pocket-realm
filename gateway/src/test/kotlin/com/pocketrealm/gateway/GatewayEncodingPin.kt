package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.GatewayContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W6-N4 R2-N4-r3-1's proposed pin: the gateway's
 * protocol channel is UTF-8 while a piped JVM defaults to the platform
 * charset (Cp1252 here) — the app's manager decodes stdout as UTF-8, so
 * an em-dash in an error detail surfaced as U+FFFD in the user-facing
 * Error lane. GatewayMain pins its stdout/stderr to UTF-8; this pin
 * runs the REAL fat jar through the bad-args path whose detail carries
 * an em-dash and asserts the em-dash survives the pipe byte-exactly.
 */
@ExtendWith(GamePortsGuard::class)
class GatewayEncodingPin {

    @Test
    fun `an em-dash error detail survives the pipe as U+2014 - never U+FFFD`() {
        // Both secret channels at once is a loud bad-args whose detail
        // carries an em-dash ("pass the secret ONE way — ...") — zero
        // network, zero binds: fully hermetic.
        val proc = launchGateway(
            "--mode", "join",
            "--secret", freshHexSecret(),
            GatewayContract.ARG_SECRET_STDIN,
            "--node", freshHexSecret(),
            "--flavor", "wotlk",
            "--max-seconds", "0",
        )
        try {
            proc.awaitExit(30_000)
            val errorLine = proc.stdoutLines().firstOrNull {
                it.startsWith(GatewayContract.ERROR_LINE_PREFIX)
            } ?: throw AssertionError(
                "the bad-args run produced no ERROR line; stdout: ${proc.stdoutLines()}"
            )
            assertTrue(
                "\u2014" in errorLine,
                "the em-dash must survive the pipe byte-exactly: $errorLine",
            )
            assertTrue(
                "\uFFFD" !in errorLine,
                "the replacement character must never appear in a protocol " +
                    "detail: $errorLine",
            )
            assertEquals(
                GatewayContract.ERR_BAD_ARGS,
                Regex("\"code\":\"([^\"]+)\"").find(
                    errorLine.removePrefix(GatewayContract.ERROR_LINE_PREFIX),
                )?.groupValues?.get(1),
            )
        } finally {
            proc.stop()
        }
    }
}
