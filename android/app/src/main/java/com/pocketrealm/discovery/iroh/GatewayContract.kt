package com.pocketrealm.discovery.iroh

/**
 * W6-N3 (docs/NOSTR-IROH-BUILD-PLAN.md stage 3): the one contract between
 * the app (Kotlin 2.0.x lane) and the iroh gateway PROCESS (Kotlin 2.2.21 /
 * JDK 21 / computer.iroh:iroh 1.1.0 — the artifact's Kotlin 2.2 metadata is
 * unreadable by the app lanes, so the transport rides a child process; D92
 * §0). Both sides compile THIS file from the shared manifest, so the line
 * protocol, exit codes, and ports can never drift.
 *
 * The gateway speaks on stdout one line at a time:
 *  - `<ready-prefix>{json}` exactly once per role, when the endpoint is
 *    bound+online (host) or dialed (join). The JSON carries the role, the
 *    node id (64 lowercase hex), the home relay URL (or null), and the muxed
 *    ports. Nothing on this channel is ever published: the HOST role's
 *    `addrs` field (the endpoint's bound UDP sockets) is the direct lane's
 *    local dial input — read by no app lane, never logged, and never
 *    carried in a listing (the listing's join descriptor is node id + home
 *    relay URL only).
 *  - `<error-prefix>{json}` on any fatal problem, followed by a nonzero
 *    exit. [ExitCode] names the failure class; the app surfaces the code
 *    and detail verbatim (the loud-failure contract).
 */
object GatewayContract {

    /** The iroh ALPN. A version bump is a wire break — new constant. */
    const val ALPN: String = "pocketrealm/gateway/1"

    const val READY_LINE_PREFIX: String = "PR_GATEWAY_READY "
    const val ERROR_LINE_PREFIX: String = "PR_GATEWAY_ERROR "

    /** Mode verbs (argv[0] of the gateway main). */
    const val MODE_HOST: String = "host"
    const val MODE_JOIN: String = "join"

    /** The mux keep-alive lane (round-6 R6-2-1): port id 0 is RESERVED —
     * the join gateway probes it every KEEPALIVE beat and the host echoes
     * one byte, so a dead host endpoint is detected in seconds (QUIC's
     * own idle timeout only surfaces at ~26s). Never a game lane. */
    const val PORT_KEEPALIVE: Int = 0

    /** The muxed game ports (ServerRuntimeContract's constants, restated:
     * this module cannot import the supervisor tree without dragging the
     * android-free manifest wider; the cross-spelling pin is the desktop
     * lane's IrohJoinGuardsTest.`the shared contract's ports are the
     * game's fixed ports`, which sees every owning tree — round-4
     * R1-r4-1 names it here so the claim is checkable). */
    const val PORT_REALM: Int = 3724
    const val PORT_WORLD: Int = 8085

    /** Argument names (single-dash long form). */
    const val ARG_MODE: String = "--mode"
    const val ARG_SECRET_HEX: String = "--secret"
    const val ARG_NODE_HEX: String = "--node"
    /** The transport secret arrives on STDIN, never argv (code-red round-4
     * R3-3: a 64-hex argv is Win32_Process-readable by any local process
     * for the endpoint's whole lifetime — the stdin channel is inherited
     * handles only). The manager is the only caller; `--secret` stays for
     * the one-machine E2E's direct spawns. */
    const val ARG_SECRET_STDIN: String = "--secret-from-stdin"
    const val ARG_RELAY_URL: String = "--relay"
    const val ARG_RELAY_ONLY: String = "--relay-only"
    const val ARG_DIRECT_ALLOWED: String = "--direct-allowed"
    const val ARG_DIRECT_ADDR: String = "--addr"
    const val ARG_FLAVOR: String = "--flavor"
    const val ARG_REALM_TARGET: String = "--realm-target"
    const val ARG_WORLD_TARGET: String = "--world-target"

    /** Relay-only is the DEFAULT (owner-adopted ruling 3): a public realm's
     * joiner dials the listing's home relay URL and nothing else — the
     * direct (hole-punched) path stays a friend-join affordance, opt-in via
     * the switch. The flag's PRESENCE means relay-only. */
    const val RELAY_ONLY_DEFAULT: Boolean = true

    /** Process exit codes (the loud-failure ladder). */
    object ExitCode {
        /** Clean stop (the app's stop verb, or the accept loop drained). */
        const val OK: Int = 0

        /** Args malformed / flavor unknown — the caller's bug, never retried. */
        const val BAD_ARGS: Int = 6

        /** The iroh dial to the host's endpoint failed (host offline, relay
         * unreachable, node id wrong). The join is refused loudly. */
        const val DIAL_FAILED: Int = 3

        /** An SMSG_REALM_LIST frame arrived that this gateway could not
         * parse under the flavor's layout — the client would dial the
         * host's raw address. The join kills itself (never leak, never
         * half-serve). */
        const val REALMLIST_REWRITE_FAILED: Int = 4

        /** A joiner-side loopback listener could not bind (another realm
         * stack — typically this app's own hosting — owns the port). */
        const val PORT_BIND_FAILED: Int = 5

        /** The loopback game stack (realmd/mangosd) the HOST gateway must
         * mux was not reachable when a client flow arrived. */
        const val HOST_STACK_UNREACHABLE: Int = 7
    }

    /** The error codes carried in the error JSON's "code" field — the
     * stable, greppable failure names (exit codes' string twins). */
    const val ERR_BAD_ARGS: String = "GATEWAY-BAD-ARGS"
    const val ERR_DIAL_FAILED: String = "GATEWAY-DIAL-FAILED"
    const val ERR_REWRITE_FAILED: String = "GATEWAY-REALMLIST-REWRITE-FAILED"
    const val ERR_PORT_BIND: String = "GATEWAY-PORT-BIND-FAILED"
    const val ERR_HOST_STACK: String = "GATEWAY-HOST-STACK-UNREACHABLE"
    const val ERR_INTERNAL: String = "GATEWAY-INTERNAL"
}
