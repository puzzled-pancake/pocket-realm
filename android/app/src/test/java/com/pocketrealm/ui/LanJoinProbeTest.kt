package com.pocketrealm.ui

import com.pocketrealm.supervisor.RuntimeFailureCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D6 (design-lan §5.2): the pre-join reachability probe's copy branches and
 * the v1 tunnel-shaped parse-time copy. The socket is faked; the honest
 * humanized outcomes (through the SHARED RuntimeFailureCopy) are pinned.
 * SHARED: this suite runs on BOTH platforms (desktop/shared-sources.json
 * shared_tests) — the same probe contract on the desktop Join pane and the
 * Android Join pane.
 */
class LanJoinProbeTest {

    @Test
    fun `a reachable realm port probes positive`() {
        val result = LanJoinProbe.probe(
            "192.168.1.50",
            socketProbe = { _, _, _ -> true },
        )
        assertTrue(result is LanJoinProbe.ProbeResult.Reachable)
        assertEquals("192.168.1.50", (result as LanJoinProbe.ProbeResult.Reachable).address)
    }

    @Test
    fun `a silent host probes unreachable and renders the firewall remedy`() {
        val result = LanJoinProbe.probe(
            "192.168.1.50",
            socketProbe = { _, _, _ -> false },
        )
        assertTrue(result is LanJoinProbe.ProbeResult.Unreachable)
        val human = RuntimeFailureCopy.humanize(
            LanJoinProbe.unreachableDetail(result as LanJoinProbe.ProbeResult.Unreachable),
        )
        assertTrue("the copy names the probed socket: $human", "192.168.1.50:3724" in human)
        assertTrue("the copy carries the firewall remedy: $human", "Windows Firewall on the host" in human)
        assertFalse("the raw marker never renders: $human", "LAN-PROBE" in human)
    }

    @Test
    fun `a connect exception probes unreachable with its cause`() {
        val result = LanJoinProbe.probe(
            "192.168.1.50",
            socketProbe = { _, _, _ -> throw java.net.SocketTimeoutException("timed out") },
        )
        assertTrue(result is LanJoinProbe.ProbeResult.Unreachable)
        assertEquals("timed out", (result as LanJoinProbe.ProbeResult.Unreachable).cause)
    }

    @Test
    fun `tunnel-shaped input is canonical ipv4 outside the lan ranges`() {
        assertTrue("public IPv4 is tunnel-shaped", LanJoinProbe.tunnelShapedAddress("8.8.8.8"))
        assertTrue("CGNAT 100.64/10 is tunnel-shaped", LanJoinProbe.tunnelShapedAddress("100.64.0.1"))
        assertFalse("private IPv4 is not tunnel-shaped", LanJoinProbe.tunnelShapedAddress("192.168.1.50"))
        assertFalse("hostnames stay with the parser's own message", LanJoinProbe.tunnelShapedAddress("example.com"))
        assertFalse(
            "non-canonical octets stay with the parser's own message",
            LanJoinProbe.tunnelShapedAddress("192.168.001.050"),
        )
    }

    @Test
    fun `the tunnel input copy points at the shipped advertise lane`() {
        assertTrue("Tailscale" in LanJoinProbe.TUNNEL_INPUT_COPY)
        assertTrue("LAN addresses only" in LanJoinProbe.TUNNEL_INPUT_COPY)
        assertFalse(
            "the not-built-yet claim is stale — the internet-play lane shipped",
            "not built yet" in LanJoinProbe.TUNNEL_INPUT_COPY,
        )
        assertTrue(
            "the copy names the Advertise field the tunnel address belongs in",
            "Advertise over the internet" in LanJoinProbe.TUNNEL_INPUT_COPY,
        )
    }
}
