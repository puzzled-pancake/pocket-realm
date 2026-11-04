package com.pocketrealm.discovery.iroh

import java.net.InetSocketAddress
import java.security.SecureRandom

/**
 * The pure decisions behind the in-app HOST gateway ([InternetHostGateway]),
 * kept free of the iroh bindings so the JVM unit lane can pin them: the
 * persisted transport secret's shape, the home-relay URL canonization the
 * listing's join descriptor needs, the mux lane a stream's header selects,
 * the realm stack dial target, and the admission/idle-reap budgets (the
 * gateway process's HostGateway numbers, restated for the in-process twin).
 */
object HostGatewayPolicy {

    /** A 32-byte iroh secret key, lowercase hex. */
    const val SECRET_HEX_LENGTH: Int = 64

    /** The endpoint-wide concurrent-flow and connection budgets: the node
     * id is world-readable once listed, so accept is bounded. */
    const val MAX_CONCURRENT_FLOWS: Int = 64
    const val MAX_CONCURRENT_CONNECTIONS: Int = 128

    /** A connection with no stream activity (game flow or keep-alive
     * probe) for this long is reaped. */
    const val IDLE_REAP_MS: Long = 60_000L

    /** The per-flow realm stack dial bound. */
    const val FLOW_DIAL_TIMEOUT_MS: Int = 5_000

    private const val SECRET_BYTES = 32
    private const val BYTE_MASK = 0xff
    private const val HEX_RADIX = 16

    /** True for a stored secret the gateway can use as-is. */
    fun isValidSecretHex(hex: String): Boolean =
        hex.length == SECRET_HEX_LENGTH && hex.all { it in '0'..'9' || it in 'a'..'f' }

    /** A fresh transport secret (generated once, then persisted so the
     * realm keeps its node id across restarts). */
    fun freshSecretHex(random: SecureRandom = SecureRandom()): String =
        ByteArray(SECRET_BYTES).also(random::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

    /** The stored secret when usable, else a fresh one; the flag says the
     * caller must persist the returned value. */
    fun secretOrFresh(stored: String, random: SecureRandom = SecureRandom()): Pair<String, Boolean> =
        if (isValidSecretHex(stored)) stored to false else freshSecretHex(random) to true

    fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { i ->
        hex.substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte()
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

    /**
     * iroh renders N0's relay URLs in the FQDN root-dot form; the
     * descriptor fence refuses an empty trailing label, so the root dot is
     * stripped here (GatewayMain.canonicalRelayUrl's twin). A bracketed
     * authority passes through untouched and is refused by the fence.
     */
    fun canonicalRelayUrl(relayUrl: String?): String? {
        if (relayUrl == null) return null
        val prefix = "https://"
        if (!relayUrl.startsWith(prefix)) return relayUrl
        val body = relayUrl.removePrefix(prefix)
        val authority = body.substringBefore('/')
        val tail = body.removePrefix(authority)
        if (authority.startsWith("[")) return relayUrl
        val host = authority.substringBefore(':').removeSuffix(".")
        val port = authority.substringAfter(':', missingDelimiterValue = "").removeSuffix(".")
        return prefix + host + (if (port.isNotEmpty()) ":$port" else "") + tail
    }

    /** The listing descriptor for a READY endpoint, or null when it has no
     * home relay or the fence refuses the relay spelling (the listing then
     * honestly says not joinable). */
    fun descriptorOrNull(nodeHex: String, relayUrl: String?): IrohJoinDescriptor? {
        val relay = canonicalRelayUrl(relayUrl) ?: return null
        return runCatching { IrohJoinDescriptor(nodeHex = nodeHex, relayUrl = relay) }.getOrNull()
    }

    /** What one accepted bi-stream asks for, from its mux header. */
    sealed interface Lane {
        data object KeepAlive : Lane
        data object Signup : Lane
        data object Hello : Lane
        data class Game(val port: Int) : Lane
        data object Refused : Lane
    }

    fun classify(header: ByteArray?): Lane {
        if (header == null || header.size < PortMux.HEADER_BYTES) return Lane.Refused
        val port = runCatching { PortMux.decodePort(header) }.getOrNull() ?: return Lane.Refused
        return when (port) {
            GatewayContract.PORT_KEEPALIVE -> Lane.KeepAlive
            GatewayContract.PORT_SIGNUP -> Lane.Signup
            GatewayContract.PORT_HELLO -> Lane.Hello
            else -> Lane.Game(port)
        }
    }

    /** The realm stack address for a game lane: the hosting session's
     * bind address (realmd/mangosd bind the LAN address while hosting, not
     * loopback). Only the two game ports are dialable. */
    fun dialTarget(port: Int, hostAddress: String): InetSocketAddress {
        require(port == GatewayContract.PORT_REALM || port == GatewayContract.PORT_WORLD) {
            "port $port is not a game port"
        }
        require(hostAddress.isNotBlank()) { "the hosting session has no bind address" }
        return InetSocketAddress(hostAddress, port)
    }

    fun idleExpired(lastStreamMs: Long, nowMs: Long): Boolean = nowMs - lastStreamMs > IDLE_REAP_MS
}
