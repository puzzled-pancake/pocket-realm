package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.GatewayContract
import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.extension.ExtendWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W6-N4 — the reconnect proof, stage 4 of NOSTR-IROH-BUILD-PLAN.md:
 * "Host network change (interface toggle / re-dial) with realm key
 * unchanged → joiner redials the same endpoint id and reconnects; no new
 * listing required."
 *
 * This file carries TWO tiers, plus the hermetic identity pin:
 *
 *  1. [GatewayEndpointIdentityPin] (always runs, hermetic): the endpoint
 *     id IS the realm key — two host binds on the same secret yield the
 *     same node id, a different secret a different id. This is the
 *     property the whole stage rests on (identity = key, not process or
 *     socket), pinned without any network.
 *
 *  2. [ReconnectRelayE2E.reconnectAfterHostRedialReusesTheSameListingDescriptor] (opt-in:
 *     -Dpocket.reconnect.relay=true, and the N0 relay must answer): the
 *     PRODUCTION dial shape — the host in default relay mode (its home
 *     relay is the listing's), the joiner RELAY-ONLY with the stored
 *     listing's home relay and nothing else. The host gateway process is
 *     RE-DIALED (killed; re-bound on the same realm key = new process,
 *     new UDP socket, fresh relay registration) while the joiner holds
 *     the ORIGINAL listing; the joiner must go loud (keep-alive loss),
 *     then re-join with the UNCHANGED descriptor and deliver a WORKING
 *     join again (both game lanes byte-verified).
 *
 *  3. [ReconnectDockerE2E] (opt-in, docker): the genuine host network
 *     IDENTITY change — the host endpoint's source IP moves between two
 *     isolated networks while the endpoint process stays up.
 *
 * The physical hotspot/CGNAT leg is NOT exercised here: this desktop has
 * one connected interface (Wi-Fi 2; Ethernet 2 disconnected) and the
 * shell is not elevated, so an interface toggle is both impossible and
 * self-severing; the owner already scheduled the physical leg for the
 * device day (its record stands). The desktop approximations above
 * are recorded as approximations in the wave evidence.
 *
 * LIVE-LEG DURABILITY: the
 * offline gate is a real JUnit ASSUMPTION (an unreachable relay ABORTS
 * the leg as skipped — never a red), the leg retries ONCE on any
 * ready/proof failure (the pass/fail rides live relay conditions), and
 * every attempt's narration + each gateway process's captured stdout is
 * archived into the wave evidence dir BEFORE anything throws — a red run
 * carries its verbatim PR_GATEWAY lines.
 */
class GatewayEndpointIdentityPin {

    /** The realm key IS the endpoint id: same secret → same node id,
     * different secret → different node id. Two real gateway processes
     * (relay-disabled: hermetic, no internet), one pin per property. */
    @Test
    fun `the endpoint id is the realm key - same secret same node, different secret different node`() {
        val secret = freshHexSecret()
        val first = launchGateway(
            "--mode", "host", "--secret", secret,
            "--relay-disabled", "--max-seconds", "4",
        )
        val second = launchGateway(
            "--mode", "host", "--secret", secret,
            "--relay-disabled", "--max-seconds", "4",
        )
        val other = launchGateway(
            "--mode", "host", "--secret", freshHexSecret(),
            "--relay-disabled", "--max-seconds", "4",
        )
        try {
            val a = first.awaitReadyOrThrow()
            val b = second.awaitReadyOrThrow()
            val c = other.awaitReadyOrThrow()
            assertEquals(a.node, b.node, "the same realm key must bind the same endpoint id")
            assertTrue(
                a.node != c.node,
                "a different realm key must bind a different endpoint id",
            )
            // A relay-disabled host has no home relay: the descriptor
            // could not exist (honest join:null), the id pin is what
            // matters here.
            assertEquals(null, a.relay)
        } finally {
            first.stop()
            second.stop()
            other.stop()
        }
    }
}

@EnabledIfSystemProperty(named = "pocket.reconnect.relay", matches = "true")
@ExtendWith(GamePortsGuard::class)
class ReconnectRelayE2E {

    /** The production-shape reconnect: host re-dial (same realm key),
     * joiner redials the ORIGINAL listing descriptor, both game lanes
     * byte-verified on BOTH sides of the network change. Real N0 relay
     * leg; see the class doc for the approximation record. ONE bounded
     * retry: a failing attempt archives its payloads, then the
     * whole leg re-runs on fresh processes; the second failure is the
     * one that surfaces. */
    @Test
    fun reconnectAfterHostRedialReusesTheSameListingDescriptor() {
        assumeRelayReachable()
        val first = runCatching { runLeg(attempt = 1) }
        val outcome = first.recoverCatching { firstFailure ->
            waveEvidenceDir().apply { mkdirs() }
            File(waveEvidenceDir(), "N4-RELAY-retry-note.txt").writeText(
                "attempt 1 failed (${firstFailure::class.simpleName}: " +
                    "${firstFailure.message}); retrying once on fresh processes\n",
            )
            runLeg(attempt = 2)
        }
        outcome.getOrThrow()
    }

    /** ONE full leg: host #1 → join #1 → exchange → host re-dial → loud
     * loss → host #2 (same key) → join #2 on the ORIGINAL descriptor →
     * exchange. Fully self-cleaning; every narration line and both
     * gateways' captured stdout land in the evidence dir whether the
     * leg passes or fails. */
    private fun runLeg(attempt: Int) {
        val narration = ArrayList<String>()
        fun note(line: String) {
            narration.add(line)
            println("N4-RELAY-$attempt: $line")
        }
        val evidenceDir = waveEvidenceDir().apply { mkdirs() }
        val hostSecret = freshHexSecret() // the REALM key: constant across the change
        // The fake realm stack on ALT loopback ports (the one-machine
        // shape): the host gateway's loopback dials MUST NOT touch the
        // joiner's own 3724/8085 listeners on this shared machine — that
        // collision recurses every client flow host→joiner→host (found
        // live by this test's first run; the --realm-target mechanism
        // exists exactly for this).
        val fakeRealmPort = freeLoopbackPort()
        val fakeWorldPort = freeLoopbackPort()
        val fakeRealm = FakeRealmServer(fakeRealmPort, fakeWorldPort)
        val fakeWorld = FakeEchoServer(fakeWorldPort)
        var host1: GatewayProc? = null
        var join1: GatewayProc? = null
        var host2: GatewayProc? = null
        var join2: GatewayProc? = null
        try {
            // 1) Host run #1 (default relay mode → its home relay is
            //    what a listing would carry).
            host1 = launchGateway(
                "--mode", "host", "--secret", hostSecret, "--max-seconds", "0",
                "--realm-target", "127.0.0.1:$fakeRealmPort",
                "--world-target", "127.0.0.1:$fakeWorldPort",
            )
            val ready1 = host1.awaitReadyOrThrow()
            assertTrue(
                !ready1.relay.isNullOrBlank(),
                "a relay-mode host READY must carry its home relay (the listing's join field)",
            )
            val d1 = IrohJoinDescriptor(nodeHex = ready1.node, relayUrl = ready1.relay!!)
            note("host #1 READY node=${d1.nodeHex.take(12)}… relay=${d1.relayUrl}")

            // 2) Join #1: the stored listing's descriptor, relay-only —
            //    the production shape.
            join1 = launchGateway(
                "--mode", "join", "--secret", freshHexSecret(),
                "--node", d1.nodeHex, "--relay-only", "--relay", d1.relayUrl,
                "--flavor", "wotlk", "--max-seconds", "0",
            )
            join1.awaitReadyOrThrow()
            note("join #1 READY on the ORIGINAL descriptor; tunnel up")
            // The tunnel works BEFORE the change (both lanes byte-exact).
            GatewayClientLane.exchangeBothLanes(
                GatewayContract.PORT_REALM, GatewayContract.PORT_WORLD,
            )
            note("pre-change exchange verified (both lanes byte-exact)")

            // 3) THE HOST NETWORK CHANGE (re-dial approximation): the
            //    host process dies and re-binds on the SAME realm key.
            host1.stop()
            val lossDeadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < lossDeadline && join1.isAlive()) {
                Thread.sleep(100)
            }
            assertTrue(
                !join1.isAlive() || join1.sawErrorLine(),
                "the joiner must go loud (loss line or exit) when the host re-dials",
            )
            // the DIAL_FAILED exit is asserted
            // UNCONDITIONALLY — a joiner that emitted the loss line but
            // lingered must still exit with the loud lane's code before
            // the leg proceeds.
            assertEquals(
                GatewayContract.ExitCode.DIAL_FAILED, join1.awaitExit(15_000),
                "the connection-loss exit must be the loud DIAL_FAILED lane",
            )
            note("host #1 re-dialed; joiner went loud (keep-alive loss lane, DIAL_FAILED exit)")
            join1.stop()

            // 4) Host run #2: SAME secret (the realm key unchanged) →
            //    the SAME descriptor ("no new listing required").
            host2 = launchGateway(
                "--mode", "host", "--secret", hostSecret, "--max-seconds", "0",
                "--realm-target", "127.0.0.1:$fakeRealmPort",
                "--world-target", "127.0.0.1:$fakeWorldPort",
            )
            val ready2 = host2.awaitReadyOrThrow()
            assertEquals(
                d1.nodeHex, ready2.node,
                "the re-dialed host must bind the SAME endpoint id (realm key unchanged)",
            )
            assertEquals(
                d1.relayUrl, ready2.relay,
                "the re-dialed host must land on the SAME home relay — a moved " +
                    "home relay would invalidate the stored listing (relay flapping " +
                    "is a finding, not a pass)",
            )
            note(
                "host #2 READY: SAME node id + SAME home relay " +
                    "(descriptor unchanged — no new listing required)",
            )

            // 5) The joiner REDIALS the ORIGINAL descriptor (fresh join
            //    identity — the production per-join secret) and the join
            //    must WORK again.
            join2 = launchGateway(
                "--mode", "join", "--secret", freshHexSecret(),
                "--node", d1.nodeHex, "--relay-only", "--relay", d1.relayUrl,
                "--flavor", "wotlk", "--max-seconds", "0",
            )
            join2.awaitReadyOrThrow()
            GatewayClientLane.exchangeBothLanes(
                GatewayContract.PORT_REALM, GatewayContract.PORT_WORLD,
            )
            note("join #2 READY on the ORIGINAL descriptor; post-change exchange verified (both lanes byte-exact)")
            note("OUTCOME=reconnect WORKING (attempt $attempt)")
        } catch (t: Throwable) {
            note("ATTEMPT FAILED: ${t::class.simpleName}: ${t.message}")
            throw t
        } finally {
            join2?.stop()
            host2?.stop()
            join1?.stop()
            host1?.stop()
            // The fake stack dies LAST (the post-reconnect exchange
            // dials it).
            fakeRealm.close()
            fakeWorld.close()
            // Archive BEFORE anything surfaces (a red run carries
            // its verbatim gateway lines).
            archivePayloads(evidenceDir, attempt, listOf("host1" to host1, "join1" to join1, "host2" to host2, "join2" to join2))
            File(evidenceDir, "n4-relay-narration-$attempt.txt").writeText(
                narration.joinToString(separator = "\n", postfix = "\n"),
            )
        }
    }

    /** Each gateway process's captured stdout (protocol lines only) into
     * the evidence dir — pass or fail (R4-4/R7-N4-1: the archived run
     * must carry what the exit record cites). */
    private fun archivePayloads(
        evidenceDir: File,
        attempt: Int,
        procs: List<Pair<String, GatewayProc?>>,
    ) {
        val body = buildString {
            for ((name, proc) in procs) {
                appendLine("=== $name ===")
                if (proc == null) {
                    appendLine("(never launched)")
                } else {
                    proc.stdoutLines().forEach { appendLine(it) }
                }
            }
        }
        File(evidenceDir, "n4-relay-payloads-$attempt.txt").writeText(body)
    }

    /** The opt-in gate's honesty leg: an offline box ABORTS the leg as
     * SKIPPED ([Assumptions.assumeTrue] → TestAbortedException — never a
     * red; R4-3/R6-N4-r1-1). [probe] is the pin's seam (a false probe
     * must abort, not fail). Probes the N0 home relay this machine was
     * measured against (N2/D92) plus regional fallbacks. */
    internal fun assumeRelayReachable(probe: ((String) -> Boolean)? = null) {
        val candidates = listOf(
            "usw1-1.relay.n0.iroh.link",
            "use1-1.relay.n0.iroh.link",
            "euw1-1.relay.n0.iroh.link",
            "aps1-1.relay.n0.iroh.link",
        )
        val reachable = candidates.any { name ->
            if (probe != null) {
                probe(name)
            } else {
                runCatching {
                    java.net.InetSocketAddress(name, 443).address?.let { addr ->
                        java.net.Socket().use { s ->
                            s.connect(java.net.InetSocketAddress(addr, 443), 3_000)
                            true
                        }
                    } ?: false
                }.getOrDefault(false)
            }
        }
        Assumptions.assumeTrue(
            reachable,
            "no N0 relay answered on 443 — the relay-leg reconnect proof " +
                "SKIPS (never fails) when the public relay fleet is " +
                "unreachable (R4-3/R6-N4-r1-1)",
        )
    }
}

/** The abort-semantics pin (R4-3/R6-N4-r1-1's proposed test): the relay
 * gate must ABORT (TestAbortedException → JUnit skip), never fail. */
class RelayGateAbortPin {

    @Test
    fun `an unreachable relay aborts the opt-in leg as skipped - never a failure`() {
        val outcome = runCatching {
            ReconnectRelayE2E().assumeRelayReachable(probe = { false })
        }
        val exception = outcome.exceptionOrNull()
        assertTrue(
            outcome.isFailure,
            "a false relay probe must abort the leg",
        )
        assertEquals(
            "org.opentest4j.TestAbortedException",
            exception!!::class.java.name,
            "the abort must ride JUnit's TestAbortedException (a SKIP), " +
                "not AssertionError (a red) — got ${exception::class.simpleName}",
        )
    }

    @Test
    fun `a reachable relay lets the gate pass without aborting`() {
        ReconnectRelayE2E().assumeRelayReachable(probe = { true })
    }
}
