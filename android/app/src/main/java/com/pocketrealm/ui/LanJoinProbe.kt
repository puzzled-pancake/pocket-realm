package com.pocketrealm.ui

import com.pocketrealm.supervisor.RealmEndpoint
import java.net.InetSocketAddress
import java.net.Socket

/**
 * D6 (design-lan §5.2): the pre-join reachability probe — a one-shot TCP
 * connect to the host's realm port (3724, ~2 s timeout), with the world
 * port (8085) shown as display-only. A mis-typed address otherwise costs a
 * full client launch plus the client's native "Unable to connect" with no
 * app-level diagnosis; the probe answers BEFORE the launch. SHARED between
 * the desktop and the Android Join pane (desktop/shared-sources.json).
 *
 * One copy branch ships in v1 (private-range target, probe fails); the
 * non-private-target branch is the v2+ internet-play lane's — and with
 * endpoint-model v2 shipped, the JOIN side accepts tunnel (CGNAT)
 * addresses through [com.pocketrealm.supervisor.RealmEndpoint.parseJoinTarget],
 * so the failure copy keys off the tunnel-shaped class
 * ([tunnelShapedAddress] + [TUNNEL_INPUT_COPY] render where the LAN BIND
 * field refuses; the join pane's tunnel branch is
 * [TunnelInvite.joinFailureHint]).
 */
object LanJoinProbe {

    /** The probe target (the realm port; ports stay non-input, shared). */
    const val PROBE_PORT = RealmEndpoint.REALM_PORT

    /** The world port, displayed as the second fixed port of the invite. */
    const val WORLD_PORT = RealmEndpoint.WORLD_PORT

    /** The one-shot probe timeout (~2 s, per design). */
    const val PROBE_TIMEOUT_MS = 2_000L

    sealed class ProbeResult {
        abstract val address: String

        /** The realm port answered — the host's realmd is reachable. */
        data class Reachable(override val address: String) : ProbeResult()

        /** Nothing answered within the timeout (or the connect failed). */
        data class Unreachable(override val address: String, val cause: String) : ProbeResult()
    }

    /** The socket seam (tests inject; production opens a real TCP socket). */
    fun interface SocketProbe {
        /** True when the TCP connect succeeded. */
        fun connect(address: String, port: Int, timeoutMs: Int): Boolean
    }

    val SYSTEM_PROBE = SocketProbe { address, port, timeoutMs ->
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, port), timeoutMs)
            true
        }
    }

    fun probe(
        address: String,
        socketProbe: SocketProbe = SYSTEM_PROBE,
        timeoutMs: Long = PROBE_TIMEOUT_MS,
    ): ProbeResult = try {
        if (socketProbe.connect(address, PROBE_PORT, timeoutMs.toInt())) {
            ProbeResult.Reachable(address)
        } else {
            ProbeResult.Unreachable(address, "connection refused")
        }
    } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
        ProbeResult.Unreachable(address, failure.message ?: failure.javaClass.simpleName)
    }

    /**
     * The raw failure detail (the D9 humanize input — the shared
     * [com.pocketrealm.supervisor.RuntimeFailureCopy] entry
     * `LAN-PROBE-UNREACHABLE` renders the two-sentence firewall copy and
     * re-extracts the address from this detail).
     */
    fun unreachableDetail(result: ProbeResult.Unreachable, timeoutMs: Long = PROBE_TIMEOUT_MS): String =
        "LAN-PROBE-UNREACHABLE: no answer from ${result.address} within ${timeoutMs} ms " +
            "(${result.cause})"

    /**
     * D6 rev-3: true when the input is a canonical numeric IPv4 that the
     * shared parser refuses for RANGE reasons — the tunnel-shaped input
     * class (public IPv4, CGNAT 100.64/10). Non-canonical input (hostnames,
     * non-canonical octets) stays with the parser's own message.
     */
    fun tunnelShapedAddress(raw: String): Boolean {
        if (!CANONICAL_IPV4.matches(raw)) return false
        val octets = raw.split('.')
        // Canonical form only (the parser's own rule: no leading zeros, in
        // range) — non-canonical input keeps the parser's message.
        val canonical = octets.size == IPV4_OCTETS &&
            octets.all { it.length == 1 || it[0] != '0' } &&
            octets.all { it.toIntOrNull() in 0..IPV4_MAX_OCTET }
        return canonical && runCatching { RealmEndpoint.parseLan(raw) }.isFailure
    }

    /** The tunnel-shaped input copy (the LAN bind field's validation text):
     * the bind field is LAN-only BY DESIGN — the internet-play lane is
     * shipped, and its address goes in the Advertise-over-the-internet
     * field, not here. */
    const val TUNNEL_INPUT_COPY: String =
        "This field binds LAN addresses only. Internet play is built: put your " +
            "tunnel address (Tailscale/ZeroTier) in the 'Advertise over the internet' " +
            "field below, and leave your LAN address here."

    /** The canonical-form bounds the parser itself enforces. */
    private const val IPV4_OCTETS = 4
    private const val IPV4_MAX_OCTET = 255

    private val CANONICAL_IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}
