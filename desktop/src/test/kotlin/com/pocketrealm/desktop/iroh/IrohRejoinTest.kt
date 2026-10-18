package com.pocketrealm.desktop.iroh

import com.pocketrealm.discovery.iroh.GatewayContract
import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W6-N4 — the reconnect proof's app-side pins (stage 4 of
 * NOSTR-IROH-BUILD-PLAN.md: the joiner "redials the same endpoint id and
 * reconnects; no new listing required").
 *
 * The transport legs live in the gateway module's ReconnectRelayE2E /
 * ReconnectDockerE2E; these two pins hold the APP half of the contract:
 *
 *  1. a re-join after a loud Error relaunches the gateway on the SAME
 *     listing descriptor — the user's re-join affordance dials the stored
 *     listing, never a re-fetched or mutated one;
 *  2. a relaunched HOST gateway on the same realm key publishes the SAME
 *     join descriptor — the heartbeat's next beat carries the identical
 *     join field, so joiners holding the old listing can still dial.
 */
class IrohRejoinTest {

    private val descriptor = IrohJoinDescriptor(
        nodeHex = "ab".repeat(32),
        relayUrl = "https://usw1-1.relay.n0.iroh.link",
    )

    private fun newManager(): IrohGatewayManager = IrohGatewayManager(
        jarOverride = java.io.File("no/such/gateway.jar"),
        javaOverride = IrohGatewayManager.DEFAULT_JAVA_21,
    )

    /** A stub gateway that prints [line] byte-clean then dies (the
     * READY/ERROR payloads carry JSON quotes `cmd echo` mangles — the
     * manager PARSES those lines, so the stub types them from a file
     * instead; W6-N4). The stub lives ~3 s (ping -n 3): the manager's
     * async READY-parse must land while the process is ALIVE — a dead
     * host invalidates its descriptor by design, so a too-short stub
     * lifetime races the parse (found by this wave's first run). */
    private fun lineThenDieStub(line: String): Process {
        val file = java.io.File.createTempFile("pr-n4-stub", ".txt")
        file.deleteOnExit()
        file.writeBytes((line + "\r\n").toByteArray(Charsets.UTF_8))
        return ProcessBuilder(
            "cmd", "/c",
            "type " + file.absolutePath.replace('/', '\\') +
                " & ping -n 3 127.0.0.1 >nul",
        ).start()
    }

    private fun awaitState(
        manager: IrohGatewayManager,
        wanted: Class<out IrohGatewayManager.JoinState>,
        timeoutMs: Long = 10_000,
    ): IrohGatewayManager.JoinState {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val state = manager.joinState.value
            if (wanted.isInstance(state)) return state
            Thread.sleep(50)
        }
        return manager.joinState.value
    }

    /** Pin 1 — the re-join affordance: after a loud Error (the N3
     * connection-loss lane's surfaced state), startJoin with the SAME
     * descriptor must relaunch with the SAME dial inputs (node id +
     * home relay — the stored listing's fields, byte-identical argv
     * shape). A re-join never needs a new listing. */
    @Test
    fun aRejoinAfterAnErrorRelaunchesOnTheSameDescriptor() {
        val manager = newManager()
        val captured = ArrayList<List<String>>()
        manager.processLauncher = { args, _ ->
            captured.add(args)
            // A stub join gateway that fails LOUD before READY (the
            // connection-loss shape the reconnect flow starts from).
            GatewayProcess(
                lineThenDieStub(
                    GatewayContract.ERROR_LINE_PREFIX +
                        "{\"code\":\"" + GatewayContract.ERR_DIAL_FAILED + "\"," +
                        "\"detail\":\"connection lost: dial again to rejoin\"}",
                ),
            )
        }
        try {
            manager.startJoin(descriptor, "wotlk", relayOnly = true)
            val first = awaitState(manager, IrohGatewayManager.JoinState.Error::class.java)
            assertTrue(
                "the first join must land in the loud Error lane",
                first is IrohGatewayManager.JoinState.Error,
            )
            // THE re-join: the same listing object, nothing re-fetched.
            manager.startJoin(descriptor, "wotlk", relayOnly = true)
            val second = awaitState(manager, IrohGatewayManager.JoinState.Error::class.java)
            assertTrue(
                "the re-join must run its own loud lane (not be refused as busy)",
                second is IrohGatewayManager.JoinState.Error,
            )
            assertEquals(
                "the re-join must relaunch the gateway process",
                2, captured.size,
            )
            assertEquals(
                "the re-join must dial the SAME listing fields — the descriptor " +
                    "(node id + home relay) is all a reconnect needs, no new listing",
                captured[0], captured[1],
            )
            assertTrue(captured[1].contains(descriptor.nodeHex))
            assertTrue(captured[1].contains(descriptor.relayUrl))
        } finally {
            manager.close()
        }
    }

    /** Pin 2 — the publisher half: a host gateway relaunched on the SAME
     * realm key publishes the SAME join descriptor (the stub carries one
     * READY payload; the REAL same-key/same-id property is pinned on the
     * gateway lane's GatewayEndpointIdentityPin and proven live by the
     * reconnect E2Es). The heartbeat's next beat after a host re-dial
     * must therefore carry the identical join field. */
    @Test
    fun aRelaunchedHostOnTheSameSecretPublishesTheSameJoinDescriptor() {
        val manager = newManager()
        val readyJson = GatewayContract.READY_LINE_PREFIX +
            "{\"role\":\"host\",\"node\":\"" + "cd".repeat(32) + "\"," +
            "\"relay\":\"https://usw1-1.relay.n0.iroh.link\"}"
        manager.processLauncher = { _, _ ->
            // A stub host: READY then immediate death (the re-dial
            // shape — the next ensure beat relaunches).
            GatewayProcess(lineThenDieStub(readyJson))
        }
        try {
            assertTrue(manager.ensureHost("ab".repeat(32)))
            val deadline = System.currentTimeMillis() + 10_000
            var first: IrohJoinDescriptor? = null
            while (System.currentTimeMillis() < deadline && first == null) {
                first = manager.hostDescriptor()
                Thread.sleep(50)
            }
            assertEquals(
                "the READY payload must become a validated descriptor",
                descriptor.copy(nodeHex = "cd".repeat(32)),
                first,
            )            // The host dies (the stub exits); the next ensure beat
            // relaunches on the SAME secret → the SAME descriptor.
            val relaunchDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < relaunchDeadline &&
                manager.hostDescriptor() != null
            ) {
                Thread.sleep(50)
            }
            assertTrue(
                "the dead host must stop advertising its descriptor",
                manager.hostDescriptor() == null,
            )
            assertTrue(manager.ensureHost("ab".repeat(32)))
            var second: IrohJoinDescriptor? = null
            val secondDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < secondDeadline && second == null) {
                second = manager.hostDescriptor()
                Thread.sleep(50)
            }
            assertEquals(
                "the relaunched host (same realm key) must publish the SAME " +
                    "join descriptor — no new listing required",
                first,
                second,
            )
        } finally {
            manager.stopHost()
            manager.close()
        }
    }

    /** W6-N4 code-red R2-N4-r2: a READY relay spelling the descriptor
     * fence refuses must NOT fail silently — the listing degrades to
     * join:null, so the refusal leaves a WARN (the log ring seam) and a
     * counted refusal, and the descriptor stays honestly null. */
    @Test
    fun aDescriptorRefusingRelaySpellingIsWarnedAndCountedNotSilent() {
        val manager = newManager()
        val refusing = "https://192.0.2.7/" // an IP literal: refused by the fence
        val readyJson = GatewayContract.READY_LINE_PREFIX +
            "{\"role\":\"host\",\"node\":\"" + "cd".repeat(32) + "\"," +
            "\"relay\":\"$refusing\"}"
        manager.processLauncher = { _, _ ->
            GatewayProcess(lineThenDieStub(readyJson))
        }
        try {
            assertTrue(manager.ensureHost("ab".repeat(32)))
            // The refusal is counted at PARSE time (R2-N4-r2's root fix:
            // validating at read time raced the host's liveness gate) —
            // wait on the counter, then hold the descriptor to nullness.
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline &&
                manager.descriptorRefusals < 1
            ) {
                Thread.sleep(50)
            }
            assertEquals(
                "a refused relay spelling must never become a descriptor",
                null, manager.hostDescriptor(),
            )
            assertEquals(
                "the refusal must be counted (the join:null degradation is " +
                    "observable, never silent)",
                1, manager.descriptorRefusals,
            )
            assertTrue(
                "the refusal must leave a WARN naming the descriptor fence",
                com.pocketrealm.desktop.DesktopLog.snapshot().any { line ->
                    line.level == "WARN" &&
                        line.message.contains("descriptor fence") &&
                        line.message.contains("join:null")
                },
            )
        } finally {
            manager.stopHost()
            manager.close()
        }
    }

    /** W6-N4 code-red R2-N4-r4-1: the STALE-GENERATION race — a dead
     * host's already-queued READY line must never repopulate the
     * descriptor cache over a NEWER generation (with a regenerated
     * realm key the stale descriptor would advertise a dead endpoint).
     * Generation A prints READY(nodeA) and dies instantly (its line
     * already buffered when generation B launches); B stays alive and
     * never prints READY. hostDescriptor() must NEVER serve nodeA while
     * generation 2 owns the slot. */
    @Test
    fun aDeadGenerationsQueuedReadyNeverServesOverANewerGeneration() {
        val manager = newManager()
        val launches = ArrayList<GatewayProcess>()
        manager.processLauncher = { _, _ -> staleGenerationLauncher(launches) }
        try {
            assertTrue(
                "generation A must launch",
                manager.ensureHost("11".repeat(32)),
            )
            // The production cadence: the relaunch beat fires AFTER the
            // dead generation is observed (ensureHost's alive-latch
            // honestly reports 'starting' while A still runs). Wait for
            // A's death, THEN beat — A's READY line is already queued,
            // which is exactly the stale-generation shape this pin hunts.
            awaitGenerationDeath(launches)
            assertTrue(
                "generation A must die before the relaunch beat",
                !launches[0].isAlive(),
            )
            assertTrue(
                "the relaunch beat must spawn generation B",
                manager.ensureHost("22".repeat(32)),
            )
            // Give generation A's parse every chance to land (it must
            // not: the slot is generation B's from the second beat on).
            repeat(60) {
                val descriptor = manager.hostDescriptor()
                assertTrue(
                    "the dead generation's descriptor (nodeA) must never be " +
                        "served over the newer generation: $descriptor",
                    descriptor == null || descriptor.nodeHex != "11".repeat(32),
                )
                Thread.sleep(50)
            }
            assertTrue(
                "both generations must have launched",
                launches.size >= 2,
            )
        } finally {
            manager.stopHost()
            manager.close()
        }
    }

    /** The stale-generation launcher: generation 0 prints READY(nodeA)
     * byte-clean and dies instantly; every later generation stays alive
     * WITHOUT printing READY. */
    private fun staleGenerationLauncher(launches: ArrayList<GatewayProcess>): GatewayProcess {
        val readyA = GatewayContract.READY_LINE_PREFIX +
            "{\"role\":\"host\",\"node\":\"" + "11".repeat(32) + "\"," +
            "\"relay\":\"https://usw1-1.relay.n0.iroh.link\"}"
        val readyAFile = java.io.File.createTempFile("pr-n4-gen-a", ".txt")
        readyAFile.deleteOnExit()
        readyAFile.writeBytes((readyA + "\r\n").toByteArray(Charsets.UTF_8))
        val process = if (launches.size == 0) {
            // Generation A: READY(nodeA) then instant death (the
            // BYTE-CLEAN line is buffered before generation B launches —
            // cmd echo mangles JSON quotes).
            GatewayProcess(
                ProcessBuilder(
                    "cmd", "/c",
                    "type " + readyAFile.absolutePath.replace('/', '\\') +
                        " & exit /b 0",
                ).start(),
            )
        } else {
            // Generation B: alive, NEVER prints READY.
            GatewayProcess(
                ProcessBuilder(
                    "cmd", "/c",
                    "ping -n 30 127.0.0.1 >nul",
                ).start(),
            )
        }
        launches += process
        return process
    }

    /** Waits (bounded) for generation 0's process to die. */
    private fun awaitGenerationDeath(launches: ArrayList<GatewayProcess>) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && launches[0].isAlive()) {
            Thread.sleep(50)
        }
    }
}
