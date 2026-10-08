package com.pocketrealm.desktop.realmstatus

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cloudflared child lane's BOUNDED process waits: the §5.7 PATH probe
 * and stop()'s child reap must never wait unbounded. A
 * `cloudflared --version` that never exits is not-on-PATH (destroyed
 * forcibly within the bound, never a wedged worker thread stuck in
 * STARTING), and a reap that outlives its bound is force-killed so stop()
 * still reaches the Job-Object terminate backstop instead of wedging the
 * tunnel lock. The pattern is the controller's own
 * powerShellCloudflaredProcesses bound (waitFor(N, TimeUnit.SECONDS) +
 * destroyForcibly); these pins hold that pattern to the child lane — a
 * regression to the unbounded waitFor() hangs the first test out.
 */
class CloudflaredPageTunnelTest {

    @Test
    fun aNeverExitingProbeIsNotOnPathAndIsReapedWithinTheBound() {
        val startedAt = System.nanoTime()
        val onPath = CloudflaredPageTunnel.probeExitsCleanly(
            pingCommand(HANG_ECHO_COUNT),
            PROBE_BOUND_SECONDS,
        )
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertFalse("a probe that never exits is not-on-PATH", onPath)
        assertTrue(
            "the hung probe is destroyed within the bound, not waited on: ${elapsedMs}ms",
            elapsedMs < MAX_ELAPSED_MS,
        )
    }

    @Test
    fun aCleanExitProbeIsOnPath() {
        assertTrue(
            "a probe that exits 0 within the bound is on-PATH",
            CloudflaredPageTunnel.probeExitsCleanly(pingCommand(1), CloudflaredPageTunnel.PROCESS_WAIT_SECONDS),
        )
    }

    @Test
    fun stopReapKillsALiveChildWithinTheBound() {
        val child = ProcessBuilder(pingCommand(HANG_ECHO_COUNT)).start()
        assertTrue("the fixture child is alive to be reaped", child.isAlive)
        val reaped = CloudflaredPageTunnel.destroyAndReap(child, CloudflaredPageTunnel.PROCESS_WAIT_SECONDS)
        assertTrue("a destroyable child is reaped within the bound", reaped)
        assertFalse("the reaped child is dead", child.isAlive)
    }

    /** A ping that would echo for ~a minute: the probe must bound IT, not
     * the other way around (the flag spelling is the platform's). */
    private fun pingCommand(echoCount: Int): List<String> =
        if (System.getProperty("os.name").lowercase().contains("windows")) {
            listOf("ping", "-n", echoCount.toString(), "127.0.0.1")
        } else {
            listOf("ping", "-c", echoCount.toString(), "127.0.0.1")
        }

    companion object {
        /** Tighter than production (the pin only needs the bound to fire). */
        private const val PROBE_BOUND_SECONDS = 1L

        /** Enough echoes that an unbounded wait could never finish first. */
        private const val HANG_ECHO_COUNT = 60

        /** Well under the 60-echo hang, well over a bounded reap's cost. */
        private const val MAX_ELAPSED_MS = 10_000L
    }
}
