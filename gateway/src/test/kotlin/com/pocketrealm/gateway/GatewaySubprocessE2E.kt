package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.GatewayContract
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * the two-process loopback join E2E on the desktop lane — the ask's
 * named test. TWO REAL PROCESSES (java -jar gateway-…-all.jar): the HOST
 * gateway muxing a fake loopback realm stack (realmd lane + world lane),
 * the JOIN gateway binding the joiner-side loopback listeners 3724/8085;
 * the fake client in THIS jvm dials 127.0.0.1:3724, performs the
 * CMSG_REALM_LIST exchange, and must receive EXACTLY the rewritten realm
 * list (the address C-string rebuilt to the joiner's own loopback world
 * target, size recomputed) — then dials 127.0.0.1:8085 and round-trips
 * raw bytes (the world lane is untouched).
 *
 * Transport posture: BOTH processes run --relay-disabled and the join is
 * --direct-allowed with an explicit loopback address — the ONE-machine
 * shape (a relay leg needs a second network island; the spike measured
 * the relay transport itself, and the relay-only dial SHAPE is pinned at
 * the args/options level here). The host-side fake stack rides the
 * loopback-pinned --realm-target/--world-target overrides because the join
 * gateway's own listeners own the real 3724/8085 on this machine.
 *
 * the process/fake-stack/client-lane machinery moved verbatim to
 * GatewayProcSupport.kt / FakeStack.kt / GatewayClientLane.kt so the
 * reconnect proofs (ReconnectRelayE2E, ReconnectDockerE2E) drive the same
 * harness; this file keeps the three scenarios unchanged.
 */
@ExtendWith(GamePortsGuard::class)
class GatewaySubprocessE2E {

    // ------------------------------------------------------------ fixtures

    // ---------------------------------------------------------------- test

    @Test
    fun twoProcessLoopbackJoinRewritesTheRealmListAndTunnelsTheWorldLane() {
        val fakeRealmPort = freeLoopbackPort()
        val fakeWorldPort = freeLoopbackPort()
        val fakeRealm = FakeRealmServer(fakeRealmPort, fakeWorldPort)
        val fakeWorld = FakeEchoServer(fakeWorldPort)
        try {
            // 1) The HOST gateway (process one) over the fake stack.
            val host = launchGateway(
                "--mode", "host",
                "--secret", SECRET_HOST,
                "--relay-disabled",
                "--realm-target", "127.0.0.1:$fakeRealmPort",
                "--world-target", "127.0.0.1:$fakeWorldPort",
                "--max-seconds", "90",
            )
            try {
                val hostReady = host.awaitReadyOrThrow()
                assertTrue(hostReady.node.length == 64, "the host node id must be 64 hex")

                // 2) The JOIN gateway (process two) — relay-only is the
                //    DEFAULT, so the direct lane is an explicit opt-in.
                val join = launchGateway(
                    "--mode", "join",
                    "--secret", SECRET_JOIN,
                    "--node", hostReady.node,
                    "--direct-allowed",
                    "--addr", hostReady.dialSocket,
                    "--flavor", "wotlk",
                    "--relay-disabled",
                    "--max-seconds", "60",
                )
                try {
                    val joinReady = join.awaitReadyOrThrow()
                    assertEquals(
                        listOf(GatewayContract.PORT_REALM, GatewayContract.PORT_WORLD),
                        joinReady.portsList,
                        "the join gateway binds BOTH game lanes",
                    )

                    // 3) The client lane: CMSG_REALM_LIST through the
                    //    tunnel; the lane itself asserts the delivered
                    //    list is the rewritten one (the joiner's own
                    //    loopback world target, never the host's
                    //    advertised address). The payload capture (the
                    //    committed privacy-grep fixtures) rides the
                    //    same exchange.
                    GatewayClientLane.realmListExchange(
                        GatewayContract.PORT_REALM,
                        System.getProperty("pocket.payload.dump"),
                    )

                    // 4) The world lane: raw bytes round-trip (no framing).
                    GatewayClientLane.worldEchoExchange(GatewayContract.PORT_WORLD)
                } finally {
                    join.stop()
                }
            } finally {
                host.stop()
            }
        } finally {
            fakeRealm.close()
            fakeWorld.close()
        }
    }

    /** Code-red round-6 R6-2-1: the HOST dying mid-join must be LOUD on
     * the joiner — within the probe window the join process either exits
     * nonzero or emits a PR_GATEWAY_ERROR line (the keep-alive watcher's
     * GATEWAY-DIAL-FAILED connection-lost); the pre-fix behavior was an
     * alive, silent joiner whose app-side Ready never left. The
     * app-side leg (a post-READY error line flips Ready → Error) is the
     * desktop IrohGatewayWatcherTest's death-drain/late-error pair. */
    @Test
    fun hostDeathMidJoinIsLoudOnTheJoiner() {
        val fakeRealmPort = freeLoopbackPort()
        val fakeWorldPort = freeLoopbackPort()
        FakeRealmServer(fakeRealmPort, fakeWorldPort).use { fake ->
            val host = launchGateway(
                "--mode", "host",
                "--secret", SECRET_HOST,
                "--relay-disabled",
                "--realm-target", "127.0.0.1:$fakeRealmPort",
                "--world-target", "127.0.0.1:$fakeWorldPort",
                "--max-seconds", "60",
            )
            try {
                val hostReady = host.awaitReadyOrThrow()
                val join = launchGateway(
                    "--mode", "join",
                    "--secret", SECRET_JOIN,
                    "--node", hostReady.node,
                    "--direct-allowed",
                    "--addr", hostReady.dialSocket,
                    "--flavor", "wotlk",
                    "--relay-disabled",
                    "--max-seconds", "60",
                )
                try {
                    join.awaitReadyOrThrow()
                    // Kill the HOST hard (no clean QUIC close — the
                    // exact mid-realm-crash shape).
                    host.stop()
                    // The keep-alive watcher must declare the loss inside
                    // the probe window: error line or exit, within 15 s.
                    val lossDeadline = System.currentTimeMillis() + 15_000
                    var loud = false
                    while (System.currentTimeMillis() < lossDeadline && !loud) {
                        if (join.sawErrorLine()) loud = true
                        if (!join.isAlive()) loud = true
                        Thread.sleep(100)
                    }
                    assertTrue(
                        loud,
                        "the joiner must go LOUD within 15s of the host's " +
                            "death (error line or exit) - never a silent " +
                            "tunnel-up state",
                    )
                } finally {
                    join.stop()
                }
            } finally {
                host.stop()
            }
        }
    }

    /** A second join while the first holds 3724/8085 must fail LOUD with
     * GATEWAY-PORT-BIND-FAILED (the process-level refusal — the app's
     * guard's ground truth). */
    @Test
    fun aSecondJoinOnTheSameInstanceRefusesLoudly() {
        val fakeRealmPort = freeLoopbackPort()
        val fakeWorldPort = freeLoopbackPort()
        FakeRealmServer(fakeRealmPort, fakeWorldPort).use { fake ->
            val host = launchGateway(
                "--mode", "host",
                "--secret", SECRET_HOST,
                "--relay-disabled",
                "--realm-target", "127.0.0.1:$fakeRealmPort",
                "--world-target", "127.0.0.1:$fakeWorldPort",
                "--max-seconds", "60",
            )
            try {
                val hostReady = host.awaitReadyOrThrow()
                val first = launchGateway(
                    "--mode", "join",
                    "--secret", SECRET_JOIN,
                    "--node", hostReady.node,
                    "--direct-allowed",
                    "--addr", hostReady.dialSocket,
                    "--flavor", "wotlk",
                    "--relay-disabled",
                    "--max-seconds", "45",
                )
                try {
                    first.awaitReadyOrThrow()
                    val second = launchGateway(
                        "--mode", "join",
                        "--secret", SECRET_JOIN2,
                        "--node", hostReady.node,
                        "--direct-allowed",
                        "--addr", hostReady.dialSocket,
                        "--flavor", "wotlk",
                        "--relay-disabled",
                        "--max-seconds", "10",
                    )
                    try {
                        second.awaitErrorOrThrow(GatewayContract.ERR_PORT_BIND)
                    } finally {
                        second.stop()
                    }
                } finally {
                    first.stop()
                }
            } finally {
                host.stop()
            }
        }
    }

    // --------------------------------------------------- process plumbing

    companion object {
        private val SECRET_HOST = "11".repeat(32)
        private val SECRET_JOIN = "22".repeat(32)
        private val SECRET_JOIN2 = "33".repeat(32)
    }

}

/** The mux header's port-id constants must equal the supervisor's game
 * ports (GatewayContract restates them; this pins the two spellings). */
class GatewayContractPortPins {

    @Test
    fun `the host admission bounds are pinned - budget removal is loud`() {
        // Code-red round-5 R4-r5-1: the endpoint-wide flow budget, the
        // connection budget, and the idle-connection reap were enforced
        // in HostGateway but pinned NOWHERE — a refactor deleting the
        // semaphores or the reap kept every suite green. The constants
        // are the contract; the reap window is 60 s (finite, nonzero).
        assertEquals(64, HostGateway.MAX_CONCURRENT_FLOWS)
        assertEquals(128, HostGateway.MAX_CONCURRENT_CONNECTIONS)
        assertEquals(5_000L, HostGateway.IDLE_REAP_POLL_MS)
        assertEquals(12, HostGateway.IDLE_REAP_POLLS)
        assertEquals(
            60_000L,
            HostGateway.IDLE_REAP_POLL_MS * HostGateway.IDLE_REAP_POLLS,
        )
    }

    @Test
    fun `the contract ports are the game's fixed ports`() {
        // 3724/8085 are the game's fixed ports (round-4 R1-r4-1: the
        // cross-spelling pin is IrohJoinGuardsTest.
        // `the shared contract's ports are the game's fixed ports` on the
        // desktop lane, whose classpath carries every owning tree).
        assertEquals(3724, GatewayContract.PORT_REALM)
        assertEquals(8085, GatewayContract.PORT_WORLD)
    }

    @Test
    fun `relay-only is the default and refuses explicit addresses`() {
        assertTrue(GatewayContract.RELAY_ONLY_DEFAULT)
        try {
            Args.parse(
                arrayOf(
                    "--mode", "join",
                    "--secret", "aa".repeat(32),
                    "--node", "bb".repeat(32),
                    "--flavor", "wotlk",
                    "--addr", "127.0.0.1:1234",
                ),
            )
            kotlin.test.fail("a relay-only join with an explicit address must refuse")
        } catch (expected: BadArgsExit) {
            assertTrue(expected.message!!.contains("relay-only"))
        }
    }

    @Test
    fun `a relay-only join without a home relay refuses - no default-set fallback`() {
        // Code-red R3-4: relay-only pins the dial to the LISTING's home
        // relay — a missing --relay must refuse, never fall back to iroh's
        // default relay set (the advertised posture is the advertised one).
        val outcome = runCatching {
            Args.parse(
                arrayOf(
                    "--mode", "join",
                    "--secret", "aa".repeat(32),
                    "--node", "bb".repeat(32),
                    "--flavor", "wotlk",
                ),
            )
        }
        kotlin.test.assertTrue(
            outcome.isFailure,
            "a relay-only join with no home relay must refuse at the args gate",
        )
    }

    @Test
    fun `an unknown flavor refuses at the args gate`() {
        // The flavor registry gate runs in main() BEFORE any network
        // effect — a one-shot process exercises exactly that path.
        val javaBin = Path(
            System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
        )
        val process = java.lang.ProcessBuilder(
            javaBin.toString(),
            "-Djna.tmpdir=E:/pocket_realm_toolchain/tmp/jna",
            "-jar", locateFatJar(),
            "--mode", "join",
            "--secret", "aa".repeat(32),
            "--node", "bb".repeat(32),
            "--flavor", "cata",
            "--relay", "https://r.example.relay",
            "--max-seconds", "1",
        ).start()
        val output = process.inputStream.bufferedReader().readText()
        val exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
        kotlin.test.assertTrue(exited, "the gateway must exit on a bad flavor")
        kotlin.test.assertEquals(GatewayContract.ExitCode.BAD_ARGS, process.exitValue())
        kotlin.test.assertTrue(
            output.contains(GatewayContract.ERR_BAD_ARGS),
            "the error line must name the code: $output",
        )
    }
}
