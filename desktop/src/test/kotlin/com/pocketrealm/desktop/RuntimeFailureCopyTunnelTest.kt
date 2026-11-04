package com.pocketrealm.desktop

import com.pocketrealm.supervisor.RuntimeFailureCopy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design-tunnel +: the interface-loss stop reason is MODE-AWARE —
 * the desktop monitor emits TUNNEL_HOST_INTERFACE_LOST for a
 * tunnel-advertised session (its loss signal is a dropped tunnel adapter,
 * not a changed LAN address), so the decoded copy must name the tunnel
 * remedy. The LAN marker keeps the verbatim pick-an-address copy.
 */
class RuntimeFailureCopyTunnelTest {
    @Test
    fun `tunnel interface loss maps to the reconnect-tunnel copy`() {
        val human = RuntimeFailureCopy.humanize("TUNNEL_HOST_INTERFACE_LOST")
        assertTrue("the remedy names reconnecting:\n$human", "Reconnect" in human)
        assertTrue("the tunnel is named as the loss:\n$human", "tunnel" in human.lowercase())
        assertFalse(
            "the LAN remedy is wrong for a tunnel host:\n$human",
            "Pick your current address" in human,
        )
        assertFalse("the raw marker never reaches the user", human.contains("TUNNEL_HOST_INTERFACE_LOST"))
    }

    @Test
    fun `lan interface loss keeps the pick-an-address remedy`() {
        val human = RuntimeFailureCopy.humanize("LAN_HOST_INTERFACE_LOST")
        assertTrue("Pick your current address" in human)
        assertFalse("the tunnel remedy must not replace the LAN one", "Reconnect" in human)
    }
}
