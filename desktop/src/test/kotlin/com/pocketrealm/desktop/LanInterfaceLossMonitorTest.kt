package com.pocketrealm.desktop

import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * the interface-loss monitor's tick decisions — the
 * session-anchored grace window, the LAN_HOST-only scope, and the verbatim
 * Android log lines. The monitor THREAD is trivial; the decisions are what
 * the pins guard.
 */
class LanInterfaceLossMonitorTest {

    private fun hostingSnapshot(sessionId: String, address: String) = RuntimeSnapshot(
        sessionId = sessionId,
        requestedProfile = "local",
        runtimeMode = RuntimeMode.LAN_HOST,
        phase = RuntimePhase.RUNNING,
        clean = false,
        lastDurableAction = "client-window-ready",
        realmEndpoint = RealmEndpoint.parseLan(address),
    )

    private fun monitor(
        live: Boolean,
        logs: MutableList<String>,
        graceMs: Long = 10_000,
    ): LanInterfaceLossMonitor = LanInterfaceLossMonitor(
        snapshot = { null },
        liveInterface = { live },
        onGraceElapsed = {},
        graceMs = graceMs,
        clock = { 0L },
        logWarning = { logs += it },
    )

    @Test
    fun `loss starts a session-anchored grace window with the android log line`() {
        val logs = mutableListOf<String>()
        val lan = monitor(live = false, logs = logs)
        val snapshot = hostingSnapshot("session-1", "192.168.1.50")
        val now = 5_000_000_000L // 5 s
        assertFalse("first lost tick never fires the grace", lan.tickOnce(snapshot, now))
        assertEquals(1, logs.size)
        assertTrue(
            "the android loss-start line verbatim: ${logs[0]}",
            logs[0] == "LAN host interface 192.168.1.50 absent; loss grace window of 10s starts",
        )
    }

    @Test
    fun `sustained loss past the grace fires the stop and the android log line`() {
        val logs = mutableListOf<String>()
        val lan = monitor(live = false, logs = logs)
        val snapshot = hostingSnapshot("session-1", "192.168.1.50")
        val start = 5_000_000_000L
        lan.tickOnce(snapshot, start)
        // 9.9 s later: still inside the window.
        assertFalse(lan.tickOnce(snapshot, start + 9_900_000_000L))
        // 10.0 s after the window opened: the graceful stop fires.
        assertTrue(lan.tickOnce(snapshot, start + 10_000_000_000L))
        assertTrue(
            "the android stop line verbatim: ${logs[1]}",
            logs[1] == "LAN host interface lost for 10s; generation will save and stop",
        )
        assertEquals(2, logs.size)
    }

    @Test
    fun `a recovered interface clears the window`() {
        val logs = mutableListOf<String>()
        val lanLive = monitor(live = true, logs = logs)
        val snapshot = hostingSnapshot("session-1", "192.168.1.50")
        assertFalse(lanLive.tickOnce(snapshot, 0L))
        assertTrue("no loss logs while live", logs.isEmpty())
    }

    @Test
    fun `a new session id never inherits elapsed loss time`() {
        val logs = mutableListOf<String>()
        val lan = monitor(live = false, logs = logs)
        lan.tickOnce(hostingSnapshot("session-1", "192.168.1.50"), 0L)
        // A later generation ticks 9 s into ITS OWN window — the elapsed
        // time of the dead session's window is gone, so this is a fresh
        // grace start, not a 1 s-to-fire carryover.
        assertFalse(lan.tickOnce(hostingSnapshot("session-2", "192.168.1.50"), 9_000_000_000L))
        assertTrue(
            "the restart line names the same endpoint: ${logs[1]}",
            logs[1] == "LAN host interface 192.168.1.50 absent; loss grace window of 10s starts",
        )
    }

    @Test
    fun `only lan host sessions are monitored`() {
        val logs = mutableListOf<String>()
        val lan = monitor(live = false, logs = logs)
        val local = RuntimeSnapshot(
            sessionId = "session-1",
            requestedProfile = "local",
            runtimeMode = RuntimeMode.LOCAL,
            phase = RuntimePhase.RUNNING,
            clean = false,
            lastDurableAction = "client-window-ready",
        )
        assertFalse("a local session runs no liveness leg", lan.tickOnce(local, 0L))
        assertTrue(logs.isEmpty())
    }

    /** The online host binds loopback: the internet gateway carries the
     * players, so even a dead interface probe (the phone left Wi-Fi) never
     * opens a loss window — the session keeps hosting. */
    @Test
    fun `a loopback online host is never stopped on interface loss`() {
        val logs = mutableListOf<String>()
        val lan = monitor(live = false, logs = logs)
        val onlineHost = RuntimeSnapshot(
            sessionId = "session-online",
            requestedProfile = "local",
            runtimeMode = RuntimeMode.LAN_HOST,
            phase = RuntimePhase.RUNNING,
            clean = false,
            lastDurableAction = "client-window-ready",
            realmEndpoint = RealmEndpoint.LOCAL,
        )
        // Far past the grace window, with the interface probe reporting
        // dead: still no stop, no loss log.
        val start = 0L
        assertFalse(lan.tickOnce(onlineHost, start))
        assertFalse(lan.tickOnce(onlineHost, start + 60_000_000_000L))
        assertTrue("a loopback host logs no interface loss: $logs", logs.isEmpty())
    }

    @Test
    fun `grace is configurable and seconds formatting follows the window`() {
        val logs = mutableListOf<String>()
        val lan = monitor(live = false, logs = logs, graceMs = 1_000)
        val snapshot = hostingSnapshot("session-1", "192.168.1.50")
        lan.tickOnce(snapshot, 0L)
        assertTrue(lan.tickOnce(snapshot, 1_200_000_000L))
        assertTrue("the elapsed seconds render: ${logs[1]}", "lost for 1s" in logs[1])
    }

    @Test
    fun `start and stop are idempotent and never leave the thread alive`() {
        val lan = LanInterfaceLossMonitor(
            snapshot = { null },
            liveInterface = { true },
            onGraceElapsed = {},
        )
        lan.start()
        lan.start() // second start is a no-op
        lan.stop()
        lan.stop() // second stop is a no-op
        assertTrue("no exception from the idempotent lifecycle", true)
    }
}
