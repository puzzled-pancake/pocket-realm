package com.pocketrealm.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table-driven guard for the crash-block gate: every waitpid-protocol value
 * crossed with the tail variants, asserting the counted/not-counted verdict.
 * The waitpid protocol is LlmExec's (0 running; 10000+exit for normal exits;
 * -signal for kills; -errno for waitpid errors) and llmexec's staging exits
 * are 124 (parent gone) / 125 (nice) / 126 (affinity) / 127 (exec).
 */
class NpuDeathAttributionTest {

    private val benignTail = "listening on 127.0.0.1:8080 | main: loading model"
    private val bindTail = "couldn't bind HTTP server socket, hostname: 127.0.0.1, port: 8080"
    private val sessionTail = "ggml-hex: failed to open session 0 : error 0x32e2"
    private val domainTail = "ggml-hex: unable to get domain struct for CDSP"

    @Test
    fun deliberateStopsNeverCountWhateverTheTailOrUptime() {
        listOf(10_000, -15).forEach { status ->
            listOf("", benignTail, bindTail, sessionTail).forEach { tail ->
                listOf(0L, 2_000L, 120_000L).forEach { uptime ->
                    assertEquals(
                        "status=$status tail=$tail uptime=$uptime",
                        NpuDeathVerdict.DELIBERATE_STOP,
                        NpuDeathAttribution.classify(status, uptime, tail),
                    )
                }
            }
        }
    }

    @Test
    fun crashSignalsAlwaysCountEvenWithABenignTailInsideTheGraceWindow() {
        // -6 SIGABRT, -9 SIGKILL (lmkd), -11 SIGSEGV (the poisoned-registry
        // null-deref), -4 SIGILL.
        listOf(-6, -9, -11, -4).forEach { status ->
            assertEquals(NpuDeathVerdict.LOAD_DEATH, NpuDeathAttribution.classify(status, 1_000L, benignTail))
            assertEquals(NpuDeathVerdict.LOAD_DEATH, NpuDeathAttribution.classify(status, 1_000L, ""))
        }
    }

    @Test
    fun echildIsUnknownNotAnAutomaticCrashVerdict() {
        // -10 is ECHILD-or-SIGUSR1: it must fall through to the tail rules,
        // never shortcut into the crash branch.
        assertEquals(NpuDeathVerdict.LOAD_DEATH, NpuDeathAttribution.classify(-10, 1_000L, sessionTail))
        assertEquals(NpuDeathVerdict.STARTUP_COLLISION, NpuDeathAttribution.classify(-10, 1_000L, bindTail))
        assertEquals(NpuDeathVerdict.STARTUP_COLLISION, NpuDeathAttribution.classify(-10, 2_000L, benignTail))
        // Empty tail with ECHILD: an NPU exec that never logged died inside
        // backend init even if another thread consumed the status.
        assertEquals(NpuDeathVerdict.LOAD_DEATH, NpuDeathAttribution.classify(-10, 1_000L, ""))
    }

    @Test
    fun llmexecStagingExitsAreNeverNpuDeaths() {
        // 124 parent-gone, 125 nice, 126 affinity, 127 exec — exactly these.
        listOf(10_124, 10_125, 10_126, 10_127).forEach { status ->
            assertEquals(NpuDeathVerdict.PRE_EXEC_FAILURE, NpuDeathAttribution.classify(status, 500L, ""))
        }
        // 128-255 are server exit codes, not staging exits: tail rules apply.
        assertEquals(NpuDeathVerdict.LOAD_DEATH, NpuDeathAttribution.classify(10_128, 500L, sessionTail))
        assertEquals(NpuDeathVerdict.STARTUP_COLLISION, NpuDeathAttribution.classify(10_128, 500L, bindTail))
    }

    @Test
    fun serverExitCodesClassifyByTailThenUptime() {
        // Bind collision: never counted, even past the grace window.
        assertEquals(NpuDeathVerdict.STARTUP_COLLISION, NpuDeathAttribution.classify(10_001, 60_000L, bindTail))
        // Session-open failure text: counted regardless of uptime.
        assertEquals(NpuDeathVerdict.LOAD_DEATH, NpuDeathAttribution.classify(10_001, 1_000L, sessionTail))
        assertEquals(NpuDeathVerdict.LOAD_DEATH, NpuDeathAttribution.classify(10_001, 60_000L, sessionTail))
        // The other real signature the round-6 marker list exists for.
        assertEquals(NpuDeathVerdict.LOAD_DEATH, NpuDeathAttribution.classify(10_001, 1_000L, domainTail))
        // Empty tail: the NPU exec never wrote a log line — died inside
        // backend init (--log-file precedes --device in argv), counts.
        assertEquals(NpuDeathVerdict.LOAD_DEATH, NpuDeathAttribution.classify(10_001, 1_000L, ""))
        // Inconclusive tail: uptime decides.
        assertEquals(NpuDeathVerdict.STARTUP_COLLISION, NpuDeathAttribution.classify(10_001, 14_999L, benignTail))
        assertEquals(NpuDeathVerdict.LOAD_DEATH, NpuDeathAttribution.classify(10_001, 15_000L, benignTail))
    }

    @Test
    fun markerListsAreTheReviewedErrorSignaturesOnly() {
        assertEquals(
            listOf(
                "failed to open session",
                "failed to create device/session",
                "failed to enable unsigned pd",
                "unable to get domain struct",
            ),
            NpuDeathAttribution.DEATH_MARKERS,
        )
        assertEquals(
            listOf(
                "couldn't bind http server socket",
                "failed to initialize http server",
                "exiting due to http server error",
            ),
            NpuDeathAttribution.COLLISION_MARKERS,
        )
        // The markers must NOT match benign teardown/startup lines that
        // carry the bare substrings of the round-5 marker list.
        val benignButSuggestive = listOf(
            "ggml-hex: releasing session: HTP0",
            "ggml-hex: releasing registry",
            "ggml-hex: failed to enable fastrpc QOS mode (non-fatal)",
            "device HTP0 registered",
        )
        benignButSuggestive.forEach { line ->
            assertEquals(
                "benign line must not be a death marker: $line",
                NpuDeathVerdict.STARTUP_COLLISION,
                NpuDeathAttribution.classify(10_001, 1_000L, line),
            )
        }
    }

    @Test
    fun procStatLivenessHandlesCommsWithParentheses() {
        assertTrue(NpuDeathAttribution.isStatAlive("1234 (llama)ser)ver) R 1 1000 1000 0 -1 0"))
        assertTrue(NpuDeathAttribution.isStatAlive("42 ((weird))name) S 1 0 0 0 -1 0"))
        assertTrue(NpuDeathAttribution.isStatAlive("7 (plain) D 1 0 0 0 -1 0"))
        assertFalse(NpuDeathAttribution.isStatAlive("42 ((weird))name) Z 1 0 0 0 -1 0"))
        assertFalse(NpuDeathAttribution.isStatAlive("1234 (llama)ser)ver) Z 1 1000 1000 0 -1 0"))
        assertFalse(NpuDeathAttribution.isStatAlive(""))
        assertFalse(NpuDeathAttribution.isStatAlive("no closing paren 123"))
    }

    @Test
    fun graceWindowConstantStaysAtTheReviewedValue() {
        assertEquals(15_000L, NpuDeathAttribution.LOAD_DEATH_GRACE_MS)
    }
}
