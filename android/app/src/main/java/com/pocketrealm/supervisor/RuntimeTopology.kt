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

    override fun equals(other: Any?): Boolean = other is RealmEndpoint &&
        address == other.address && realmPort == other.realmPort && worldPort == other.worldPort
    override fun hashCode(): Int = 31 * (31 * address.hashCode() + realmPort) + worldPort
    override fun toString(): String = "$address:$realmPort/$worldPort"

    companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val REALM_PORT = 3724
        const val WORLD_PORT = 8085
        val LOCAL = RealmEndpoint(LOOPBACK_ADDRESS)

        fun parseLan(raw: String): RealmEndpoint {
            val octets = parseCanonicalIpv4(raw)
            require(isPrivateOrLinkLocal(octets)) {
                "LAN endpoint must be a private or IPv4 link-local address"
            }
            return RealmEndpoint(raw)
        }

        fun parseStored(raw: String): RealmEndpoint =
            if (raw == LOOPBACK_ADDRESS) LOCAL else parseLan(raw)

        private fun parseCanonicalIpv4(raw: String): IntArray {
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

/** Immutable launch identity for one supervisor generation. */
data class RuntimeLaunchSpec(
    val mode: RuntimeMode,
    val profileId: String,
    val endpoint: RealmEndpoint,
    val includeClient: Boolean,
    val allowLanPlayers: Boolean = false,
) {
    init {
        require(PROFILE.matches(profileId)) { "invalid profile identity" }
        when (mode) {
            RuntimeMode.LOCAL -> {
                require(endpoint.isLoopback) { "local mode requires loopback" }
                require(!allowLanPlayers) { "local mode cannot authorize LAN exposure" }
            }
            RuntimeMode.LAN_JOIN -> {
                require(endpoint.isPrivateOrLinkLocal) { "LAN join requires a private endpoint" }
                require(includeClient) { "LAN join is client-only and requires the client" }
                require(!allowLanPlayers) { "LAN join cannot authorize server exposure" }
            }
            RuntimeMode.LAN_HOST -> {
                require(endpoint.isPrivateOrLinkLocal) {
                    "LAN hosting requires an exact private interface endpoint"
                }
                require(allowLanPlayers) { "LAN hosting requires explicit allowLanPlayers authorization" }
            }
        }
    }

    /** Client-only requirements must never leak into a server-only realm start. */
    val requiresClient: Boolean get() = mode == RuntimeMode.LAN_JOIN || includeClient
    val requiresServer: Boolean get() = mode != RuntimeMode.LAN_JOIN

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

    companion object {
        const val SPEC_SCHEMA = 1
        private val PROFILE = Regex("[A-Za-z0-9._-]{1,64}")

        fun local(profileId: String, includeClient: Boolean = false) = RuntimeLaunchSpec(
            RuntimeMode.LOCAL, profileId, RealmEndpoint.LOCAL, includeClient, allowLanPlayers = false,
        )

        fun lanJoin(profileId: String, address: String) = RuntimeLaunchSpec(
            RuntimeMode.LAN_JOIN, profileId, RealmEndpoint.parseLan(address), includeClient = true,
            allowLanPlayers = false,
        )

        fun lanHost(profileId: String, address: String, includeClient: Boolean = false) = RuntimeLaunchSpec(
            RuntimeMode.LAN_HOST, profileId, RealmEndpoint.parseLan(address), includeClient,
            allowLanPlayers = true,
        )

        fun fromJson(raw: String): RuntimeLaunchSpec {
            require(raw.toByteArray(Charsets.UTF_8).size <= 4_096) { "launch spec is too large" }
            return fromJson(JSONObject(raw))
        }

        fun fromJson(value: JSONObject): RuntimeLaunchSpec {
            require(value.getInt("schema") == SPEC_SCHEMA) { "unsupported launch spec schema" }
            val allowed = setOf(
                "schema", "mode", "profileId", "endpoint", "realmPort", "worldPort", "includeClient",
                "allowLanPlayers",
            )
            val actual = buildSet { value.keys().forEachRemaining(::add) }
            require(actual == allowed) { "launch spec schema mismatch" }
            require(value.getInt("realmPort") == RealmEndpoint.REALM_PORT &&
                value.getInt("worldPort") == RealmEndpoint.WORLD_PORT) {
                "LAN ports are fixed in launch schema v1"
            }
            val mode = RuntimeMode.valueOf(value.getString("mode"))
            val endpoint = if (mode == RuntimeMode.LOCAL) {
                require(value.getString("endpoint") == RealmEndpoint.LOOPBACK_ADDRESS) {
                    "local launch endpoint must be loopback"
                }
                RealmEndpoint.LOCAL
            } else RealmEndpoint.parseLan(value.getString("endpoint"))
            return RuntimeLaunchSpec(
                mode = mode,
                profileId = value.getString("profileId"),
                endpoint = endpoint,
                includeClient = value.getBoolean("includeClient"),
                allowLanPlayers = value.getBoolean("allowLanPlayers"),
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
