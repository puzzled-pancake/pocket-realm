package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.GatewayContract
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W6-N4 code-red round 4, R5-r4-N4-1's proposed pin: GatewayProc's
 * await lanes destructively poll the line queue — the evidence
 * archivers must read the NON-consuming transcript, so a red relay-leg
 * run archives every launched process's verbatim PR_GATEWAY lines even
 * when an await consumed them before the failure.
 */
class GatewayProcTranscriptPin {

    private fun stubProcess(lines: List<String>): GatewayProc {
        val file = File.createTempFile("pr-transcript-pin", ".txt")
        file.deleteOnExit()
        file.writeText(lines.joinToString(separator = "\r\n", postfix = "\r\n"))
        return GatewayProc(
            ProcessBuilder(
                "cmd", "/c",
                "type " + file.absolutePath.replace('/', '\\') +
                    " & ping -n 2 127.0.0.1 >nul",
            ).start(),
        )
    }

    @Test
    fun `stdoutLines serves the full transcript after the awaits drained the queue`() {
        val ready = GatewayContract.READY_LINE_PREFIX +
            "{\"role\":\"host\",\"node\":\"" + "aa".repeat(32) + "\"," +
            "\"relay\":\"https://usw1-1.relay.n0.iroh.link/\",\"ports\":[3724,8085]}"
        val error = GatewayContract.ERROR_LINE_PREFIX +
            "{\"code\":\"GATEWAY-DIAL-FAILED\",\"detail\":\"connection lost\"}"
        val proc = stubProcess(listOf(ready, error))
        try {
            // Drain destructively, exactly as the relay leg's awaits do.
            proc.awaitReadyOrThrow()
            proc.awaitErrorOrThrow(GatewayContract.ERR_DIAL_FAILED)
            // The transcript must still carry BOTH lines verbatim.
            val transcript = proc.stdoutLines()
            assertEquals(
                listOf(ready, error),
                transcript,
                "the transcript must survive the destructive awaits verbatim",
            )
        } finally {
            proc.stop()
        }
    }

    @Test
    fun `a red run archives non-empty sections for every launched process`() {
        // The R5-r4-N4-1 assertion shape: archivePayloads reads
        // stdoutLines() — prove the section for a proc whose lines an
        // await already consumed is NON-EMPTY and carries the verbatim
        // PR_GATEWAY line (the round-4 pre-fix artifact had empty
        // host/host2/join2 sections).
        val line = GatewayContract.READY_LINE_PREFIX +
            "{\"role\":\"join\",\"node\":\"" + "bb".repeat(32) + "\",\"relay\":null,\"ports\":[3724,8085]}"
        val proc = stubProcess(listOf(line))
        try {
            proc.awaitReadyOrThrow()
            val section = proc.stdoutLines().joinToString("\n")
            assertTrue(
                GatewayContract.READY_LINE_PREFIX in section &&
                    "bb".repeat(32) in section,
                "the archived section must carry the verbatim READY line",
            )
        } finally {
            proc.stop()
        }
    }
}
