package com.pocketrealm.desktop

import com.pocketrealm.supervisor.RealmEndpoint
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * the desktop interface seam over the shared
 * [com.pocketrealm.supervisor.LanInterfacePolicy] — the `DesktopRealmlist`
 * precedent (a desktop twin wrapping a shared projection, never retuning the
 * shared file's Android behavior).
 *
 * Why the twin exists (measured, design-lan): the shared scan sorts by
 * the `wlan`/`eth` NAME prefix, and `NetworkInterface.isVirtual` reports
 * false for at least the "Hyper-V Virtual Ethernet Adapter" on real Windows
 * machines — so a virtual adapter's unreachable private address (e.g.
 * 172.21.208.1) sorts FIRST and a one-click host defaults to an address no
 * LAN guest can reach. The shared file stays untouched; this twin filters
 * the SAME scan by DISPLAY NAME before the Settings quick-picks and before
 * 's liveness re-checks, and prefers the interface that owns the default
 * route (resolved in-process via a UDP `connect()` to a private-range probe
 * address — no packets of consequence are sent).
 *
 * The stored-address validation ([RealmEndpoint.parseLan]) stays unchanged:
 * the filter only shapes which interfaces the app OFFERS and RE-PROVES.
 */
class DesktopLanInterfaces(
    /** The candidate scan seam (tests inject; production [SYSTEM_SCAN]). */
    private val scan: () -> List<Candidate> = SYSTEM_SCAN,
    /** The default-route probe seam (tests inject; production
     * [SYSTEM_ROUTE_PROBE]). Null result = route unknown, no preference. */
    private val defaultRouteAddress: () -> String? = SYSTEM_ROUTE_PROBE,
) {
    /** One scanned interface: display name + driver name + its private IPv4. */
    data class Candidate(val displayName: String, val name: String, val endpoint: RealmEndpoint)

    /**
     * The usable private IPv4s: the shared base filters (up, not loopback,
     * not point-to-point, not flagged virtual, `wlan`/`eth` name prefix)
     * PLUS the display-name exclusion, ordered default-route first. The
     * quick-pick list and every liveness verdict come from this one method.
     */
    fun available(): List<RealmEndpoint> = candidates().map { it.endpoint }

    fun candidates(): List<Candidate> {
        val routeOwner = runCatching { defaultRouteAddress() }.getOrNull()
        return runCatching { scan() }.getOrDefault(emptyList())
            .filter { candidate -> !EXCLUDED_DISPLAY_NAME.containsMatchIn(candidate.displayName) }
            .sortedWith(
                compareBy<Candidate>(
                    // the default-route owner is the one address LAN
                    // guests can actually reach — it leads the list.
                    { if (routeOwner != null && it.endpoint.address == routeOwner) 0 else 1 },
                    { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 },
                    { if (it.endpoint.address.startsWith("169.254.")) 1 else 0 },
                    { it.name },
                    { it.endpoint.address },
                ),
            )
    }

    /**
     * D1's liveness predicate over the FILTERED view: the endpoint must be
     * the current address of an interface this twin would offer. A virtual
     * adapter's address is live-but-unusable — it refuses here exactly like
     * a missing interface (design-lan).
     */
    fun isCurrentPrivateInterface(endpoint: RealmEndpoint): Boolean =
        endpoint.isPrivateOrLinkLocal && available().any { it.address == endpoint.address }

    companion object {
        /**
         * D3's exclusion (case-insensitive substring over the DISPLAY name):
         * Windows virtual/hidden adapters whose `isVirtual` flag lies. The
         * words come from the design's list verbatim.
         */
        private val EXCLUDED_DISPLAY_NAME = Regex(
            "(?i)Hyper-V|Virtual|vEthernet|Wi-Fi Direct|WAN Miniport|Loopback|WFP|LightWeight Filter",
        )

        /** Production scan: the shared policy's base filters + display names. */
        val SYSTEM_SCAN: () -> List<Candidate> = {
            NetworkInterface.getNetworkInterfaces()?.let(java.util.Collections::list).orEmpty()
                .filter { network ->
                    network.isUp && !network.isLoopback && !network.isPointToPoint &&
                        !network.isVirtual &&
                        (network.name.startsWith("wlan") || network.name.startsWith("eth"))
                }
                .flatMap { network ->
                    network.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { address ->
                            val literal = address.hostAddress ?: return@mapNotNull null
                            runCatching {
                                Candidate(
                                    network.displayName ?: network.name,
                                    network.name,
                                    RealmEndpoint.parseLan(literal),
                                )
                            }.getOrNull()
                        }
                }
                .distinctBy { it.endpoint.address }
        }

        /**
         * Production default-route probe: connect an unconnected datagram
         * socket at a private-range address (nothing is delivered — UDP
         * connect only fixes the peer) and read the local socket address
         * the routing table chose. Fails closed to null (no preference).
         */
        val SYSTEM_ROUTE_PROBE: () -> String? = {
            DatagramSocket().use { socket ->
                socket.connect(InetAddress.getByName(DEFAULT_ROUTE_PROBE_ADDRESS), 9)
                (socket.localAddress as? Inet4Address)?.hostAddress
            }
        }

        /** The route-probe target: private-range, deliberately unroutable. */
        const val DEFAULT_ROUTE_PROBE_ADDRESS = "192.168.255.255"
    }
}
