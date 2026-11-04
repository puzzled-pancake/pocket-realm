package com.pocketrealm.supervisor

import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** The only supported network topologies. Missing/legacy state always resolves to LOCAL. */
enum class RuntimeMode { LOCAL, LAN_JOIN, LAN_HOST }

/**
 * Canonical, numeric IPv4 endpoint. Ports are deliberately not user input in v1.
 * No parser in this file performs DNS or accepts URI/hostname syntax.
 */
class RealmEndpoint private constructor(
    val address: String,
    val realmPort: Int = REALM_PORT,
    val worldPort: Int = WORLD_PORT,
) {
    val isLoopback: Boolean get() = address == LOOPBACK_ADDRESS
    val isPrivateOrLinkLocal: Boolean get() = !isLoopback

    /** True for a CGNAT 100.64/10 address — the numeric tunnel-adapter form
     * (design-tunnel). Only the JOIN lane and the advertised-address
     * type accept it; the bind path never does. */
    val isCgnatTunnel: Boolean
        get() = isCgnat(address.split('.').map { it.toInt() }.toIntArray())

    override fun equals(other: Any?): Boolean = other is RealmEndpoint &&
        address == other.address && realmPort == other.realmPort && worldPort == other.worldPort
    override fun hashCode(): Int = 31 * (31 * address.hashCode() + realmPort) + worldPort
    override fun toString(): String = "$address:$realmPort/$worldPort"

    companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val REALM_PORT = 3724
        const val WORLD_PORT = 8085
        val LOCAL = RealmEndpoint(LOOPBACK_ADDRESS)

        /**
         * The raw conf bind value for "all interfaces" (design-tunnel:
         * tunnel hosting binds the wildcard so the advertised tunnel address
         * is actually reachable). Deliberately NOT constructible as a
         * [RealmEndpoint] — the typed launch/bind path stays strict
         * private/link-local; only the conf writers accept this literal.
         */
        const val WILDCARD_BIND = "0.0.0.0"

        fun parseLan(raw: String): RealmEndpoint {
            val octets = parseCanonicalIpv4(raw)
            require(isPrivateOrLinkLocal(octets)) {
                "LAN endpoint must be a private or IPv4 link-local address"
            }
            return RealmEndpoint(raw)
        }

        /**
         * The HOST bind grammar: a strict private/link-local interface
         * address (LAN players) or loopback (the online host — the game
         * binds 127.0.0.1 and the internet gateway forwards to it, so
         * hosting needs no Wi-Fi or LAN at all).
         */
        fun parseHostBind(raw: String): RealmEndpoint =
            if (raw == LOOPBACK_ADDRESS) LOCAL else parseLan(raw)

        /**
         * Design-tunnel (endpoint model v2): the JOIN side accepts the
         * same widened grammar the host side advertises — a private,
         * link-local, OR tunnel (CGNAT 100.64/10) canonical numeric IPv4. The
         * bind path ([parseLan]) stays unchanged; a CGNAT address can never
         * become a bind endpoint, and public IPv4 / hostnames are refused
         * here exactly as there.
         */
        fun parseJoinTarget(raw: String): RealmEndpoint {
            val octets = parseCanonicalIpv4(raw)
            require(isPrivateOrLinkLocal(octets) || isCgnat(octets)) {
                "join target must be a private, link-local, or tunnel " +
                    "(CGNAT 100.64/10) numeric IPv4 address"
            }
            return RealmEndpoint(raw)
        }

        fun parseStored(raw: String): RealmEndpoint =
            if (raw == LOOPBACK_ADDRESS) LOCAL else parseLan(raw)

        /** True when the octets sit in the CGNAT shared range 100.64/10 —
         * the numeric form tailnet/tunnel adapters carry (design-tunnel
         * the one widening the shared security review bounds;
         * hostnames stay out of v1). */
        internal fun isCgnat(o: IntArray): Boolean = o[0] == 100 && o[1] in 64..127

        internal fun parseCanonicalIpv4(raw: String): IntArray {
            require(raw.length in 7..15 && raw.all { it == '.' || it in '0'..'9' }) {
                "endpoint must be a canonical numeric IPv4 address"
            }
            require(raw.count { it == '.' } == 3) {
                "endpoint must contain four IPv4 octets"
            }
            val pieces = raw.split('.')
            require(pieces.size == 4) { "endpoint must contain four IPv4 octets" }
            val octets = pieces.map { piece ->
                require(piece.isNotEmpty() && piece.length <= 3 &&
                    (piece.length == 1 || piece[0] != '0')) {
                    "endpoint must use canonical decimal IPv4 octets"
                }
                piece.toInt().also { require(it in 0..255) { "IPv4 octet is out of range" } }
            }.toIntArray()
            require(octets.joinToString(".") == raw) { "endpoint is not canonical IPv4" }
            return octets
        }

        private fun isPrivateOrLinkLocal(o: IntArray): Boolean =
            o[0] == 10 ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 169 && o[1] == 254)
    }
}

/**
 * The ADVERTISED realm address (design-tunnel, endpoint model v2):
 * what the realmlist row and the friend's realmlist.wtf carry, as opposed
 * to the [RealmEndpoint] the servers BIND. The bind endpoint keeps today's
 * strict grammar exactly; this type is the one widening, and in v1 it is
 * numeric-only: canonical IPv4 in the loopback, private (RFC1918),
 * link-local, or CGNAT (100.64/10) ranges. Hostnames (MagicDNS/ts.net) and
 * public IPv4 (the UPnP lane) stay out until their own reviewed steps —
 * numeric form only, per remote-accounts 's "literal tunnel IPs in v1".
 *
 * Keeping the value canonical digits-and-dots also keeps the realmlist
 * UPDATE's injection guard trivially closed (the desktop seam interpolates;
 * the typed parse is that guard, and the projection seam re-checks length
 * against the varchar(32) column, design-tunnel).
 */
class AdvertisedAddress private constructor(
    val value: String,
) {
    val isLoopback: Boolean get() = value == RealmEndpoint.LOOPBACK_ADDRESS

    /** The tailnet-style numeric tunnel form (100.64/10). */
    val isCgnatTunnel: Boolean
        get() = RealmEndpoint.isCgnat(value.split('.').map { it.toInt() }.toIntArray())

    override fun equals(other: Any?): Boolean = other is AdvertisedAddress && other.value == value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        /** The MariaDB-lane realmlist column cap (upstream varchar(32));
         * numeric addresses always fit, the projection seam re-checks. */
        const val MAX_LENGTH = 32

        fun parse(raw: String): AdvertisedAddress {
            require(raw.length <= MAX_LENGTH) {
                "advertised address must fit the realmlist column ($MAX_LENGTH characters)"
            }
            val octets = RealmEndpoint.parseCanonicalIpv4(raw)
            // parseLan's octet check excludes loopback by design (it is the
            // BIND grammar); the ADVERTISED set adds loopback explicitly —
            // the LOCAL default and the cloudflared recipe's dial address.
            val lan = raw != RealmEndpoint.LOOPBACK_ADDRESS &&
                runCatching { RealmEndpoint.parseLan(raw) }.isSuccess
            require(raw == RealmEndpoint.LOOPBACK_ADDRESS || lan || RealmEndpoint.isCgnat(octets)) {
                "advertised address must be loopback, private, link-local, or " +
                    "tunnel (CGNAT 100.64/10) numeric IPv4"
            }
            return AdvertisedAddress(raw)
        }
    }
}
/**
 * Immutable launch identity for one supervisor generation.
 */
data class RuntimeLaunchSpec(
    val mode: RuntimeMode,
    val profileId: String,
    val endpoint: RealmEndpoint,
    val includeClient: Boolean,
    val allowLanPlayers: Boolean = false,
    /**
     * Design-tunnel (endpoint model v2): the optional ADVERTISED
     * address the realmlist row carries, split from the [endpoint] the
     * servers bind. Null everywhere by default (LOCAL/LAN behave
     * byte-identically to schema 1); only LAN_HOST may advertise, and the
     * value is a typed [AdvertisedAddress] — numeric loopback/private/
     * link-local/CGNAT IPv4 only in v1.
     */
    val advertisedAddress: AdvertisedAddress? = null,
) {
    init {
        require(PROFILE.matches(profileId)) { "invalid profile identity" }
        require(advertisedAddress == null || mode == RuntimeMode.LAN_HOST) {
            "only LAN hosting may advertise an override address"
        }
        when (mode) {
            RuntimeMode.LOCAL -> {
                require(endpoint.isLoopback) { "local mode requires loopback" }
                require(!allowLanPlayers) { "local mode cannot authorize LAN exposure" }
            }
            RuntimeMode.LAN_JOIN -> {
                // Loopback is the in-app internet join: the client dials the
                // local iroh gateway's listeners, never a local server.
                require(endpoint.isPrivateOrLinkLocal || endpoint.isLoopback) {
                    "LAN join requires a private endpoint"
                }
                require(includeClient) { "LAN join is client-only and requires the client" }
                require(!allowLanPlayers) { "LAN join cannot authorize server exposure" }
            }
            RuntimeMode.LAN_HOST -> {
                // Loopback binds the online host (the internet gateway
                // forwards to it); a private interface binds LAN players.
                require(endpoint.isPrivateOrLinkLocal || endpoint.isLoopback) {
                    "LAN hosting requires an exact private interface endpoint"
                }
                if (endpoint.isLoopback) {
                    require(!allowLanPlayers) {
                        "loopback hosting cannot authorize LAN exposure"
                    }
                    require(advertisedAddress == null) {
                        "loopback hosting cannot advertise an override address"
                    }
                } else {
                    require(allowLanPlayers) {
                        "LAN hosting requires explicit allowLanPlayers authorization"
                    }
                }
            }
        }
    }

    /** Client-only requirements must never leak into a server-only realm start. */
    val requiresClient: Boolean get() = mode == RuntimeMode.LAN_JOIN || includeClient
    val requiresServer: Boolean get() = mode != RuntimeMode.LAN_JOIN

    /**
     * Design-tunnel: the address the realmlist row must carry — the
     * explicit advertisement when set, else the bind endpoint (today's
     * behavior). This is what the projection seam writes.
     */
    val advertisedOrDefault: String
        get() = advertisedAddress?.value ?: endpoint.address

    /**
     * True when the advertisement diverges from the bind endpoint: the
     * friend can only resolve the advertised value (a tunnel address), so
     * the servers must bind the wildcard (design-tunnel 's conflict rule
     * — one realm entry for everyone, and it resolves only inside the
     * tunnel).
     */
    val tunnelAdvertising: Boolean
        get() = advertisedAddress != null && advertisedAddress.value != endpoint.address

    fun componentPlan(): List<RuntimeComponent> = when (mode) {
        RuntimeMode.LAN_JOIN -> listOf(RuntimeComponent.CLIENT)
        RuntimeMode.LOCAL, RuntimeMode.LAN_HOST -> buildList {
            add(RuntimeComponent.DATABASE)
            add(RuntimeComponent.REALM)
            add(RuntimeComponent.WORLD)
            if (includeClient) add(RuntimeComponent.CLIENT)
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schema", SPEC_SCHEMA)
        .put("mode", mode.name)
        .put("profileId", profileId)
        .put("endpoint", endpoint.address)
        .put("realmPort", RealmEndpoint.REALM_PORT)
        .put("worldPort", RealmEndpoint.WORLD_PORT)
        .put("includeClient", includeClient)
        .put("allowLanPlayers", allowLanPlayers)
        .also { if (advertisedAddress != null) it.put("advertisedAddress", advertisedAddress.value) }

    companion object {
        const val SPEC_SCHEMA = 2
        private val PROFILE = Regex("[A-Za-z0-9._-]{1,64}")

        fun local(profileId: String, includeClient: Boolean = false) = RuntimeLaunchSpec(
            RuntimeMode.LOCAL, profileId, RealmEndpoint.LOCAL, includeClient, allowLanPlayers = false,
        )

        /**
         * The JOIN target (design-tunnel): a private/link-local LAN
         * host, or — schema-2 widening — the host's numeric tunnel address
         * (CGNAT 100.64/10). Public IPv4 and hostnames stay refused.
         */
        fun lanJoin(profileId: String, address: String) = RuntimeLaunchSpec(
            RuntimeMode.LAN_JOIN, profileId, RealmEndpoint.parseJoinTarget(address),
            includeClient = true, allowLanPlayers = false,
        )

        /**
         * The internet join: client-only against the in-app iroh gateway's
         * loopback listeners (the gateway rewrites the realm list to them).
         */
        fun internetJoin(profileId: String) = RuntimeLaunchSpec(
            RuntimeMode.LAN_JOIN, profileId, RealmEndpoint.LOCAL,
            includeClient = true, allowLanPlayers = false,
        )

        /**
         * The host factory: a private interface address is today's LAN host
         * (LAN players authorized); the loopback literal is the ONLINE host
         * (the game binds 127.0.0.1, the internet gateway forwards to it,
         * and no LAN exposure exists to authorize).
         */
        fun lanHost(
            profileId: String,
            address: String,
            includeClient: Boolean = false,
            advertised: String? = null,
        ) = RuntimeLaunchSpec(
            RuntimeMode.LAN_HOST, profileId, RealmEndpoint.parseHostBind(address), includeClient,
            allowLanPlayers = address != RealmEndpoint.LOOPBACK_ADDRESS,
            advertisedAddress = advertised?.let { AdvertisedAddress.parse(it) },
        )

        fun fromJson(raw: String): RuntimeLaunchSpec {
            require(raw.toByteArray(Charsets.UTF_8).size <= 4_096) { "launch spec is too large" }
            return fromJson(JSONObject(raw))
        }

        fun fromJson(value: JSONObject): RuntimeLaunchSpec {
            require(value.getInt("schema") == SPEC_SCHEMA) { "unsupported launch spec schema" }
            val allowed = setOf(
                "schema", "mode", "profileId", "endpoint", "realmPort", "worldPort", "includeClient",
                "allowLanPlayers", "advertisedAddress",
            )
            val actual = buildSet { value.keys().forEachRemaining(::add) }
            // "advertisedAddress" is OPTIONAL (schema 2's one addition):
            // exactly the base eight, or base eight + the advertised key —
            // anything else (unknown keys, advertised without its
            // base shape) still refuses.
            require(actual == allowed || actual == allowed - "advertisedAddress") {
                "launch spec schema mismatch"
            }
            require(value.getInt("realmPort") == RealmEndpoint.REALM_PORT &&
                value.getInt("worldPort") == RealmEndpoint.WORLD_PORT) {
                "LAN ports are fixed in launch schema v2"
            }
            val mode = RuntimeMode.valueOf(value.getString("mode"))
            val endpoint = when (mode) {
                RuntimeMode.LOCAL -> {
                    require(value.getString("endpoint") == RealmEndpoint.LOOPBACK_ADDRESS) {
                        "local launch endpoint must be loopback"
                    }
                    RealmEndpoint.LOCAL
                }
                RuntimeMode.LAN_JOIN -> value.getString("endpoint").let { raw ->
                    if (raw == RealmEndpoint.LOOPBACK_ADDRESS) RealmEndpoint.LOCAL
                    else RealmEndpoint.parseJoinTarget(raw)
                }
                RuntimeMode.LAN_HOST -> RealmEndpoint.parseHostBind(value.getString("endpoint"))
            }
            val advertised = when {
                !value.has("advertisedAddress") || value.isNull("advertisedAddress") -> null
                else -> AdvertisedAddress.parse(value.getString("advertisedAddress"))
            }
            return RuntimeLaunchSpec(
                mode = mode,
                profileId = value.getString("profileId"),
                endpoint = endpoint,
                includeClient = value.getBoolean("includeClient"),
                allowLanPlayers = value.getBoolean("allowLanPlayers"),
                advertisedAddress = advertised,
            )
        }
    }
}

/** Read-only proof that LAN host binding targets one current private IPv4 interface. */
object LanInterfacePolicy {
    fun available(): List<RealmEndpoint> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.let(java.util.Collections::list).orEmpty()
            .filter {
                it.isUp && !it.isLoopback && !it.isPointToPoint && !it.isVirtual &&
                    (it.name.startsWith("wlan") || it.name.startsWith("eth"))
            }
            .flatMap { network ->
                network.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { address ->
                        val literal = address.hostAddress ?: return@mapNotNull null
                        runCatching { network.name to RealmEndpoint.parseLan(literal) }.getOrNull()
                    }
            }
            .distinctBy { it.second.address }
            .sortedWith(compareBy<Pair<String, RealmEndpoint>>(
                { if (it.first.startsWith("wlan") || it.first.startsWith("eth")) 0 else 1 },
                { if (it.second.address.startsWith("169.254.")) 1 else 0 },
                { it.first },
                { it.second.address },
            ))
            .map { it.second }
    }.getOrDefault(emptyList())

    fun isCurrentPrivateInterface(endpoint: RealmEndpoint): Boolean =
        endpoint.isPrivateOrLinkLocal && available().any { it.address == endpoint.address }

    fun selectForHosting(): RealmEndpoint = available().firstOrNull()
        ?: throw IllegalStateException("No active private IPv4 LAN interface is available")
}

/**
 * The tunnel-adapter lane (design-tunnel): an explicit NAME-PREFIX
 * allowlist — Tailscale, ZeroTier — never a relaxation of the LAN filter,
 * which keeps excluding point-to-point/virtual adapters so LAN hosting
 * keeps its existing guarantee. Tunnel adapters are exactly the adapters
 * the LAN scan drops (point-to-point/virtual, other names), carrying the
 * numeric [AdvertisedAddress] the tunnel mode advertises (CGNAT 100.64/10
 * for a tailnet; RFC1918 for a ZeroTier-managed range — both parse).
 */
object TunnelInterfacePolicy {
    /** Case-insensitive name/display-name prefixes, checked with startsWith. */
    val PREFIXES = listOf("tailscale", "zerotier")

    /** The pure allowlist leg (unit-pinned; the scan feeds it both names). */
    fun isTunnelAdapter(name: String, displayName: String): Boolean {
        val driver = name.lowercase()
        val friendly = displayName.lowercase()
        return PREFIXES.any { prefix -> driver.startsWith(prefix) || friendly.startsWith(prefix) }
    }

    /** The live tunnel-adapter IPv4s (typed; unparseable entries skipped,
     * never thrown — the Settings quick-picks render from this). */
    fun available(): List<AdvertisedAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.let(java.util.Collections::list).orEmpty()
            .filter { network ->
                network.isUp && !network.isLoopback &&
                    isTunnelAdapter(network.name, network.displayName ?: network.name)
            }
            .flatMap { network ->
                network.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .mapNotNull { address ->
                        val literal = address.hostAddress ?: return@mapNotNull null
                        runCatching { AdvertisedAddress.parse(literal) }.getOrNull()
                    }
            }
            .distinctBy { it.value }
            .sortedBy { it.value }
    }.getOrDefault(emptyList())

    /** True when [advertised] is the current address of a tunnel adapter —
     * the mode-aware watch/preflight predicate (design-tunnel). */
    fun isCurrentTunnelInterface(advertised: AdvertisedAddress): Boolean =
        available().any { it.value == advertised.value }
}

/**
 * Design-tunnel (the mode/predicate reconciliation design-lan
 * owns the table for): WHICH interface-loss leg a hosting session's health
 * monitor runs, keyed by the session's ADVERTISED address. Plain LAN
 * hosting watches the private LAN endpoint exactly as design-lan
 * specifies; a tunnel-advertised session watches the TUNNEL adapter (so
 * physical-LAN churn never kills a tunnel realm whose path never touched
 * the LAN); a loopback advertisement (the documented cloudflared recipe)
 * has no interface to lose — its loss signal is the upstream forwarder's
 * health, so the monitor runs NO interface leg. Shared so
 * both platforms' monitors decide identically.
 */
sealed class InterfaceWatch {
    /** No interface leg: loopback advertisement (cloudflared) — or a
     * malformed advertisement value, which the launch gate already
     * refused and the monitor treats as no-watch rather than inventing a
     * stop. */
    object None : InterfaceWatch()

    /** Plain LAN hosting: watch the private LAN [RuntimeEndpoint]. */
    object Lan : InterfaceWatch()

    /** Tunnel-advertised hosting: watch the tunnel adapter carrying
     * [advertised]; physical-LAN churn must not count as loss. */
    data class Tunnel(val advertised: AdvertisedAddress) : InterfaceWatch()

    companion object {
        /**
         * @param bindAddress the session's BIND endpoint address (the
         * snapshot's realm endpoint): a loopback bind is the online host —
         * the internet gateway carries the players, so there is no physical
         * interface to lose and the monitor runs NO interface leg.
         */
        fun forSession(bindAddress: String?, advertisedRaw: String?): InterfaceWatch {
            if (bindAddress == RealmEndpoint.LOOPBACK_ADDRESS) return None
            if (advertisedRaw.isNullOrBlank()) return Lan
            val advertised = runCatching { AdvertisedAddress.parse(advertisedRaw) }.getOrNull()
                ?: return None
            if (advertised.isLoopback) return None
            return Tunnel(advertised)
        }
    }
}

/**
 * The online host's endpoint choice (shared by both platforms): the game
 * binds loopback unless LAN players are switched on AND a private interface
 * exists — a phone on mobile data, or with its interface down, hosts online
 * regardless. LAN players with no interface also fall back to loopback
 * (there is nothing to expose).
 */
object HostOnlineEndpointPolicy {
    fun selectAddress(lanPlayers: Boolean, available: List<RealmEndpoint>): String =
        if (lanPlayers) {
            available.firstOrNull()?.address ?: RealmEndpoint.LOOPBACK_ADDRESS
        } else {
            RealmEndpoint.LOOPBACK_ADDRESS
        }
}
