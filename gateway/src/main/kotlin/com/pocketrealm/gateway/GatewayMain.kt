package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.GatewayContract
import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import com.pocketrealm.discovery.iroh.RealmListRewrite
import computer.iroh.Endpoint
import computer.iroh.EndpointOptions
import computer.iroh.IrohException
import computer.iroh.LogLevel
import computer.iroh.RelayMode
import computer.iroh.presetN0
import computer.iroh.setLogLevel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Base64

/**
 * the iroh gateway process (see build.gradle.kts for why a
 * process). Modes per [GatewayContract]:
 *
 *   host: muxes the loopback game stack (realmd 3724 + mangosd 8085) over
 *         ONE iroh endpoint — every accepted connection carries a 2-byte
 *         port id ([com.pocketrealm.discovery.iroh.PortMux]) then RAW game
 *         bytes. Prints one READY line {node, relay} for the listing's
 *         join descriptor; NEVER an IP.
 *
 *   join: dials the host's node id (relay-only by default: the LISTING's
 *         home relay URL is the only relay this endpoint uses — no direct
 *         addresses are ever dialed), binds the joiner-side loopback
 *         listeners 3724/8085, and proxies client bytes over the tunnel;
 *         the 3724 lane rebuilds every SMSG_REALM_LIST to the joiner's own
 *         loopback world target (the ONLY place the rewrite exists).
 *
 * Failure posture (the loud-join contract): every fatal path prints one
 * ERROR line {code, detail} and exits with the matching [GatewayContract.ExitCode]
 * — dial failure, rewrite failure, port-bind clash, unreachable host stack,
 * bad args. Nothing is swallowed; the app surfaces the code verbatim.
 */
fun main(args: Array<String>) {
    // W6-N4 code-red R2-N4-r3-1: the protocol channel is UTF-8 — the
    // app's manager decodes the child's stdout with Charsets.UTF_8, but
    // a piped JVM defaults stdout/stderr to the platform charset
    // (Cp1252 here), so every em-dash in an error detail surfaced as
    // U+FFFD in the user-facing Error lane. Pin the streams BEFORE any
    // protocol line is printed.
    val utf8Out = java.io.PrintStream(
        java.io.FileOutputStream(java.io.FileDescriptor.out),
        true,
        Charsets.UTF_8,
    )
    val utf8Err = java.io.PrintStream(
        java.io.FileOutputStream(java.io.FileDescriptor.err),
        true,
        Charsets.UTF_8,
    )
    System.setOut(utf8Out)
    System.setErr(utf8Err)
    computer.iroh.setLogLevel(LogLevel.WARN)
    System.setProperty(
        "jna.tmpdir",
        System.getProperty("jna.tmpdir", "E:/pocket_realm_toolchain/tmp/jna"),
    )
    val exitCode: Int = try {
        val parsed = Args.parse(args)
        if (parsed.mode == GatewayContract.MODE_JOIN) {
            // The flavor registry refuses an unknown id BEFORE any network
            // effect (the layout is the join's contract; fail at the gate).
            try {
                RealmListRewrite.flavorFor(parsed.flavorId!!)
            } catch (flavor: Exception) {
                throw BadArgsExit(flavor.message ?: "unknown flavor")
            }
        }
        when (parsed.mode) {
            GatewayContract.MODE_HOST -> HostGateway.run(parsed)
            GatewayContract.MODE_JOIN -> JoinGateway.run(parsed)
            else -> throw BadArgsExit("unknown mode '${parsed.mode}'")
        }
    } catch (bad: BadArgsExit) {
        GatewayMain.errorLine(GatewayContract.ERR_BAD_ARGS, bad.message ?: "bad args")
        GatewayContract.ExitCode.BAD_ARGS
    } catch (dial: DialFailedExit) {
        GatewayMain.errorLine(GatewayContract.ERR_DIAL_FAILED, dial.message ?: "dial failed")
        GatewayContract.ExitCode.DIAL_FAILED
    } catch (rewrite: RewriteFailedExit) {
        GatewayMain.errorLine(GatewayContract.ERR_REWRITE_FAILED, rewrite.message ?: "rewrite failed")
        GatewayContract.ExitCode.REALMLIST_REWRITE_FAILED
    } catch (bind: PortBindFailedExit) {
        GatewayMain.errorLine(GatewayContract.ERR_PORT_BIND, bind.message ?: "port bind failed")
        GatewayContract.ExitCode.PORT_BIND_FAILED
    } catch (stack: HostStackUnreachableExit) {
        GatewayMain.errorLine(GatewayContract.ERR_HOST_STACK, stack.message ?: "host stack unreachable")
        GatewayContract.ExitCode.HOST_STACK_UNREACHABLE
    } catch (unexpected: Exception) {
        GatewayMain.errorLine(
            GatewayContract.ERR_INTERNAL,
            "${unexpected::class.simpleName}: ${unexpected.message}",
        )
        unexpected.printStackTrace()
        GatewayContract.ExitCode.BAD_ARGS + 1 // 7 is taken; internal is loud either way
    }
    kotlin.system.exitProcess(exitCode)
}

object GatewayMain {

    /** (found by the first real-relay exercise of the descriptor
     * ladder): iroh renders N0's relay URLs in the FQDN root-dot form
     * ("https://usw1-1.relay.n0.iroh.link./" — N2's captured READY line),
     * and [IrohJoinDescriptor]'s fail-closed host ladder refuses an
     * empty trailing label — the app would have degraded EVERY host
     * descriptor to join:null. The fix lives at the PRODUCER: this line
     * protocol carries the canonical no-dot URL (DNS resolution is
     * identical for the joiner's RelayMode.customFromUrls), and the
     * descriptor boundary stays strict (it still refuses the dot form —
     * canonization never widens the public trust boundary). */
    fun canonicalRelayUrl(relayUrl: String?): String? {
        if (relayUrl == null) return null
        val prefix = "https://"
        if (!relayUrl.startsWith(prefix)) return relayUrl
        val body = relayUrl.removePrefix(prefix)
        val authority = body.substringBefore('/') // host[:port], possibly root-dotted
        val tail = body.removePrefix(authority) // "" or the "/" + path
        // R5-4 (round-6 minor): a bracketed IPv6-literal authority is
        // OUT OF DESIGN (the home relay is FQDN-addressed; the descriptor
        // fence refuses IP-literal hosts) — pass it through UNTOUCHED
        // rather than mangling it at the first colon.
        if (authority.startsWith("[")) return relayUrl
        val host = authority.substringBefore(':').removeSuffix(".")
        // R2-N4-r3 (round-6 minor): the root dot is stripped from the
        // PORT segment too ("relay.example.com:443./") so the doc
        // comment's 'exactly one FQDN root dot from the authority' holds
        // for every segment.
        val port = authority.substringAfter(':', missingDelimiterValue = "").removeSuffix(".")
        return prefix + host + (if (port.isNotEmpty()) ":$port" else "") + tail
    }

    /** One stdout protocol line, flushed (the app reads line-wise).
     * [boundSockets] carries the endpoint's actual UDP bind addresses —
     * the DIRECT lane's dial input (the one-machine E2E); the app's
     * relay-only joins never read it. Never an address in a LISTING —
     * this line is the app's local process channel, not a payload. */
    fun readyLine(
        role: String,
        nodeHex: String,
        relayUrl: String?,
        ports: List<Int>,
        boundSockets: List<String> = emptyList(),
    ) {
        val relay = canonicalRelayUrl(relayUrl)
        val relayJson = relay?.let { "\"$it\"" } ?: "null"
        // boundSockets() strings arrive QUOTED (the FFI renders the addr's
        // Debug form) — strip the wrapping quotes so the JSON stays valid.
        val addrsJson = boundSockets.joinToString(",") { socket ->
            "\"" + socket.trim('"') + "\""
        }
        println(
            GatewayContract.READY_LINE_PREFIX +
                "{\"role\":\"$role\",\"node\":\"$nodeHex\",\"relay\":$relayJson," +
                "\"ports\":${ports.joinToString(",", "[", "]")}," +
                "\"addrs\":[$addrsJson]}",
        )
        System.out.flush()
    }

    fun errorLine(code: String, detail: String) {
        val safe = detail.replace("\\", "\\\\").replace("\"", "\\\"")
        println(GatewayContract.ERROR_LINE_PREFIX + "{\"code\":\"$code\",\"detail\":\"$safe\"}")
        System.out.flush()
        System.err.println("gateway error: $code: $detail")
        System.err.flush()
    }
}

// --------------------------------------------------------------------- args

/** Typed argv; [BadArgsExit] on anything malformed (fail before side effects). */
data class Args(
    val mode: String,
    val secretHex: String,
    val nodeHex: String?,
    val relayUrl: String?,
    val relayOnly: Boolean,
    val directAddresses: List<String>,
    val flavorId: String?,
    val maxSeconds: Long,
    val relayDisabled: Boolean,
    /** Host-mode loopback dial targets (loopback-pinned overrides; the
     * defaults are the game's fixed ports on 127.0.0.1). */
    val realmTarget: java.net.InetSocketAddress? = null,
    val worldTarget: java.net.InetSocketAddress? = null,
) {
    companion object {
        fun parse(argv: Array<String>): Args {
            var mode: String? = null
            var secret: String? = null
            // Code-red round-4 R3-3: the app lane passes the transport
            // secret over the inherited stdin pipe, never argv (a 64-hex
            // argv is Win32_Process-readable by any local process).
            var secretFromStdin = false
            var node: String? = null
            var relay: String? = null
            var relayOnly = GatewayContract.RELAY_ONLY_DEFAULT
            var relayDisabled = false
            val directAddresses = ArrayList<String>()
            var flavor: String? = null
            var realmTarget: String? = null
            var worldTarget: String? = null
            var maxSeconds = 0L
            var i = 0
            while (i < argv.size) {
                when (argv[i]) {
                    GatewayContract.ARG_MODE -> mode = value(argv, ++i, argv[i - 1])
                    GatewayContract.ARG_SECRET_HEX -> secret = value(argv, ++i, argv[i - 1])
                    GatewayContract.ARG_SECRET_STDIN -> secretFromStdin = true
                    GatewayContract.ARG_NODE_HEX -> node = value(argv, ++i, argv[i - 1])
                    GatewayContract.ARG_RELAY_URL -> relay = value(argv, ++i, argv[i - 1])
                    GatewayContract.ARG_RELAY_ONLY -> relayOnly = true
                    "--relay-disabled" -> relayDisabled = true
                    "--direct-allowed" -> relayOnly = false
                    GatewayContract.ARG_DIRECT_ADDR -> directAddresses.add(
                        value(argv, ++i, argv[i - 1]),
                    )
                    GatewayContract.ARG_FLAVOR -> flavor = value(argv, ++i, argv[i - 1])
                    GatewayContract.ARG_REALM_TARGET -> realmTarget = value(argv, ++i, argv[i - 1])
                    GatewayContract.ARG_WORLD_TARGET -> worldTarget = value(argv, ++i, argv[i - 1])
                    "--max-seconds" -> maxSeconds = value(argv, ++i, argv[i - 1]).toLongOrNull()
                        ?: throw BadArgsExit("--max-seconds must be an integer")
                    else -> throw BadArgsExit("unknown argument '${argv[i]}'")
                }
                i += 1
            }
            val resolvedMode = mode ?: throw BadArgsExit("--mode host|join required")
            if (secret != null && secretFromStdin) {
                throw BadArgsExit(
                    "pass the secret ONE way — '${GatewayContract.ARG_SECRET_STDIN}' " +
                        "(the app lane; read from stdin) or '${GatewayContract.ARG_SECRET_HEX}'",
                )
            }
            val resolvedSecret = (
                secret
                    ?: (if (secretFromStdin) readSecretFromStdin() else null)
                ) ?: throw BadArgsExit(
                "a transport secret is required ('${GatewayContract.ARG_SECRET_STDIN}' " +
                    "reads the 64-hex line from stdin, or '${GatewayContract.ARG_SECRET_HEX} <hex>')",
            )
            if (resolvedSecret.length != 64 || resolvedSecret.any { !it.isHexDigit() }) {
                throw BadArgsExit("--secret must be 64 hex characters (32 bytes)")
            }
            if (resolvedMode == GatewayContract.MODE_JOIN) {
                val nodeValue = node ?: throw BadArgsExit("join needs --node <32-byte hex>")
                if (nodeValue.length != 64 || nodeValue.any { !it.isHexDigit() }) {
                    throw BadArgsExit("--node must be 64 hex characters")
                }
                flavor ?: throw BadArgsExit("join needs --flavor vanilla|tbc|wotlk")
                // The privacy shape, fail-closed: a RELAY-ONLY join dials
                // the listing's home relay and NOTHING else — explicit
                // addresses are refused outright (they would be IPs). The
                // direct lane (--direct-allowed, the friend-join affordance)
                // may carry them.
                if (relayOnly && directAddresses.isNotEmpty()) {
                    throw BadArgsExit(
                        "relay-only joins never dial explicit addresses — " +
                            "pass --direct-allowed only for a trusted friend join",
                    )
                }
                if (!relayOnly && relay.isNullOrBlank() && directAddresses.isEmpty()) {
                    throw BadArgsExit(
                        "join needs --relay <home relay url> (relay-only) or " +
                            "--addr <ip:port> (--direct-allowed)",
                    )
                }
                // a RELAY-ONLY join without the LISTING's home relay
                // would silently fall back to iroh's default relay set —
                // the documented posture is the listing's home relay and
                // nothing else, so the missing --relay refuses loudly.
                if (relayOnly && relay.isNullOrBlank()) {
                    throw BadArgsExit(
                        "relay-only join needs --relay <the listing's home " +
                            "relay url> — without it the dial would use the " +
                            "default relay set instead of the advertised one",
                    )
                }
                // R2N4R2-2 (round-6 minor): a syntactically invalid relay
                // URL used to defer to the FFI and surface as
                // GATEWAY-INTERNAL with a NULL detail — refuse at the args
                // gate by running the SAME fence ladder the descriptor
                // runs (IrohJoinDescriptor.validateRelayUrl), so the
                // refusal carries a non-null actionable reason and the
                // shapes can never drift apart.
                relay?.let { relayValue ->
                    IrohJoinDescriptor.validateRelayUrl(relayValue)?.let { refusal ->
                        throw BadArgsExit("--relay: $refusal")
                    }
                }
            }
            // Host-side loopback targets: the contract defaults are the
            // game's fixed ports; the override exists for ONE machine's
            // loopback E2E (host stack and join listeners cannot share the
            // same ports) and dev lanes. Shape "host:port", fail-closed.
            fun parseTarget(raw: String?): java.net.InetSocketAddress? =
                raw?.let {
                    val host = it.substringBeforeLast(':')
                    val port = it.substringAfterLast(':').toIntOrNull()
                        ?: throw BadArgsExit("target '$it' must be host:port")
                    if (host != "127.0.0.1" && host != "localhost") {
                        throw BadArgsExit("target '$it' must stay on loopback — the host " +
                            "gateway muxes the LOCAL realm stack only")
                    }
                    java.net.InetSocketAddress(host, port)
                }

            return Args(
                mode = resolvedMode,
                secretHex = resolvedSecret.lowercase(),
                nodeHex = node?.lowercase(),
                relayUrl = relay,
                relayOnly = relayOnly,
                directAddresses = directAddresses,
                flavorId = flavor,
                maxSeconds = maxSeconds,
                relayDisabled = relayDisabled,
                realmTarget = parseTarget(realmTarget),
                worldTarget = parseTarget(worldTarget),
            )
        }

        private fun value(argv: Array<String>, index: Int, flag: String): String {
            if (index >= argv.size) throw BadArgsExit("$flag needs a value")
            return argv[index]
        }

        /** The stdin leg of the transport secret (code-red round-4 R3-3):
         * one trimmed line (64 hex chars), empty handle → loud BadArgs —
         * never a silently-empty identity. Package-private for the test
         * that pins the pipe shape. */
        internal fun readSecretFromStdin(): String? {
            // `in` is a hard keyword in Kotlin — the standard stream is
            // spelled with backticks; read at parse time so a test's
            // System.setIn swap is honored.
            val line = java.io.BufferedReader(
                java.io.InputStreamReader(System.`in`, Charsets.UTF_8),
            ).readLine() ?: return null
            return line.trim().ifEmpty { null }
        }

        // W6-N4 code-red R2-6-1: ASCII-ONLY — Char.isDigit() is
        // Unicode-aware (U+0663-class digits pass it), so a UTF-8 stdin
        // secret rode this check and SecretKeys.fromHex derived identity
        // bytes from a non-hex spelling. The gate's own copy is true
        // only for 0-9a-fA-F.
        private fun Char.isHexDigit(): Boolean =
            (this in '0'..'9') || (this in 'a'..'f') || (this in 'A'..'F')
    }
}

class BadArgsExit(message: String) : RuntimeException(message)
class DialFailedExit(message: String) : RuntimeException(message)
class RewriteFailedExit(message: String) : RuntimeException(message)
class PortBindFailedExit(message: String) : RuntimeException(message)
class HostStackUnreachableExit(message: String) : RuntimeException(message)

// ------------------------------------------------------------- iroh plumbing

/** Builds the endpoint options: the E:-pinned JNA tmpdir aside, the shape
 * is the spike's measured configuration. [relayDisabled] selects the
 * loopback/offline posture (tests, and documented dev lanes): no relay at
 * all, direct addressing only. */
internal fun endpointOptions(parsed: Args, relayDisabled: Boolean): EndpointOptions {
    val secret = SecretKeys.fromHex(parsed.secretHex)
    val mode = when {
        // Relay-only join: the LISTING's home relay is the ONLY relay this
        // endpoint will join through (the honest v1 shape the FFI can
        // express; see the module doc for what it does and does not pin).
        parsed.mode == GatewayContract.MODE_JOIN && parsed.relayOnly &&
            !parsed.relayUrl.isNullOrBlank() ->
            RelayMode.customFromUrls(listOf(parsed.relayUrl!!))

        relayDisabled -> RelayMode.disabled()

        else -> RelayMode.defaultMode()
    }
    return EndpointOptions(
        // TRANSPORT PRIVACY POSTURE (named per the pin
        // test_gateway_build_pins_the_relay_only_transport_posture):
        // presetN0() installs PkarrPublisher::n0_dns() +
        // DnsAddressLookup::n0_dns() — it is NOT discovery-free — and the
        // endpoint's pkarr TXT record stays free of direct addresses ONLY
        // through the publisher's DEFAULT AddrFilter::relay_only
        // (iroh 1.0.2 src/endpoint/presets.rs N0 + pkarr.rs:168/210 "By
        // default [AddrFilter::relay_only] is used. This avoids leaking
        // IP addresses"; verified on the Cargo.lock-pinned iroh 1.0.2 /
        // iroh-ffi 1.1.0). The ffi 1.1.0 EndpointOptions exposes no
        // address-filter knob, so this default IS the mechanism: an iroh
        // bump that drops it must fail this wave's pin until re-verified
        // (the consult's own-TXT-record probe is the W7 re-verify item).
        preset = presetN0(),
        secretKey = secret.toBytes(),
        alpns = listOf(GatewayContract.ALPN.toByteArray()),
        relayMode = mode,
        bindAddr = null,
        protocols = null,
    )
}

internal object SecretKeys {
    fun fromHex(hex: String): computer.iroh.SecretKey {
        val bytes = ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return computer.iroh.SecretKey.fromBytes(bytes)
    }
}

internal fun hexLower(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }

/** Binds the endpoint and waits (bounded) for online; returns the pair the
 * READY line needs. With relays disabled (the loopback E2E lane) there is
 * no home relay to reach — `online()` would wait forever, so the bind is
 * usable immediately and the online wait is skipped. */
internal suspend fun bindOnline(parsed: Args, relayDisabled: Boolean = false): Endpoint {
    val endpoint = Endpoint.bind(endpointOptions(parsed, relayDisabled))
    if (relayDisabled) return endpoint
    try {
        withTimeout(ONLINE_TIMEOUT_MS) { endpoint.online() }
    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
        endpoint.close()
        throw DialFailedExit(
            "endpoint did not come online within ${ONLINE_TIMEOUT_MS / 1000}s " +
                "(relay unreachable?)",
        )
    } catch (e: IrohException) {
        runCatching { endpoint.close() }
        throw DialFailedExit("endpoint bind/online failed: ${e.message}")
    }
    return endpoint
}

internal const val ONLINE_TIMEOUT_MS: Long = 30_000L

/** Park helper for the accept loops (tests pass --max-seconds instead). */
internal fun parkOrTimeout(maxSeconds: Long) {
    if (maxSeconds > 0) {
        Thread.sleep(maxSeconds * 1000)
    } else {
        // Park until the app's stop verb (process destroy) ends us.
        Thread.currentThread().join()
    }
}

/** Base64 helper kept from the spike (payload digests in debug logs). */
internal fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
