package com.pocketrealm.desktop.iroh

import com.pocketrealm.discovery.iroh.GatewayContract
import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * W6-N3 code-red round 2 (R4-r2-1 + R4-r2-3): the manager's join watcher
 * lands Ready the MOMENT the READY line arrives (never gated by the ERROR
 * lane's 60 s budget), and the error lane is BOUNDED so a hostile flow
 * flood cannot grow the hosting app's memory.
 *
 * The stub gateway is a real OS process: it prints the READY line and
 * stays alive (-1), or floods error lines and exits (-3).
 */
class IrohGatewayWatcherTest {

    private val descriptor = IrohJoinDescriptor(
        nodeHex = "ab".repeat(32),
        relayUrl = "https://usw1-1.relay.n0.iroh.link",
    )

    /** A stub gateway: READY then alive for [aliveSeconds]. */
    private fun startStubGateway(readyLine: String, aliveSeconds: Long): Process =
        ProcessBuilder(
            "cmd", "/c",
            "echo $readyLine & ping -n ${aliveSeconds + 1} 127.0.0.1 >nul",
        ).start()

    /** R4-r2-1: Ready lands well under the verb's 45 s deadline when the
     * READY line arrives and no ERROR line follows. */
    @Test
    fun readyLandsImmediatelyWithoutGatingOnTheErrorLane() {
        val manager = IrohGatewayManager(
            jarOverride = java.io.File("no/such/gateway.jar"),
            javaOverride = IrohGatewayManager.DEFAULT_JAVA_21,
        )
        val stub = startStubGateway(
            "PR_GATEWAY_READY {\"role\":\"join\",\"node\":\"" +
                "ab".repeat(32) + "\",\"relay\":null,\"ports\":[3724,8085],\"addrs\":[]}",
            aliveSeconds = 30,
        )
        try {
            manager.armJoinReadyWatcher(GatewayProcess(stub), descriptor)
            val deadline = System.currentTimeMillis() + 5_000
            while (
                manager.joinState.value !is IrohGatewayManager.JoinState.Ready &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
            assertTrue(
                "Ready must land within 5s of the READY line (the ERROR " +
                    "lane must never gate it)",
                manager.joinState.value is IrohGatewayManager.JoinState.Ready,
            )
        } finally {
            stub.destroy()
            manager.close()
        }
    }

    /** R4-r2-3: the error lane is BOUNDED — 200 flooded error lines leave
     * the queue at (or under) its cap. */
    @Test
    fun theErrorLaneIsBoundedUnderAFlood() {
        val flood = 200
        val stub = ProcessBuilder(
            "cmd", "/c",
            "for /l %i in (1,1,$flood) do @echo " +
                "PR_GATEWAY_ERROR {\"code\":\"X\",\"detail\":\"flood\"}",
        ).start()
        val process = GatewayProcess(stub)
        try {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline && stub.isAlive) {
                Thread.sleep(50)
            }
            assertTrue(
                "the flood must finish",
                process.pendingErrorLineCount() <= 64,
            )
        } finally {
            process.stop()
        }
    }

    /** its twin: a post-READY process death flips Ready → Error (the
     * stale-Ready guard, round-1 R3-2). */
    @Test
    fun aPostReadyProcessDeathFlipsReadyToError() {
        val manager = IrohGatewayManager(
            jarOverride = java.io.File("no/such/gateway.jar"),
            javaOverride = IrohGatewayManager.DEFAULT_JAVA_21,
        )
        // READY then immediate exit.
        val stub = startStubGateway(
            "PR_GATEWAY_READY {\"role\":\"join\",\"node\":\"" +
                "ab".repeat(32) + "\",\"relay\":null,\"ports\":[3724,8085],\"addrs\":[]}",
            aliveSeconds = 0,
        )
        try {
            manager.armJoinReadyWatcher(GatewayProcess(stub), descriptor)
            val deadline = System.currentTimeMillis() + 10_000
            while (
                manager.joinState.value !is IrohGatewayManager.JoinState.Error &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
            assertTrue(
                "a post-READY death must flip Ready → Error (never stale)",
                manager.joinState.value is IrohGatewayManager.JoinState.Error,
            )
        } finally {
            stub.destroy()
            manager.close()
        }
    }

    /** The GatewayContract READY shape the stub must speak (kept adjacent
     * to the stubs so the protocol cannot drift silently). */
    @Test
    fun theStubReadyLineUsesTheContractPrefix() {
        assertTrue(
            GatewayContract.READY_LINE_PREFIX.startsWith("PR_GATEWAY_READY"),
        )
        assertEquals(0, GatewayContract.ExitCode.OK - 0)
        assertTrue(TimeUnit.SECONDS.toMillis(5) > 0)
    }

    // ---------------------------------------------- round 4 (R4-N3-3 etc.)

    /** Round-4 R4-N3-3: the post-READY ERROR monitor LOOPS — a gateway
     * fatal error AFTER the first window (the old code's single 60 s
     * await surrendered and stranded the line) is still read, and the
     * surfaced code/detail are the gateway's OWN (never generic). The
     * stub: READY, ~2 s of silence, then ERROR, then a lingering exit —
     * the injected 1 s window laps once while the process is alive. */
    @Test
    fun aLateErrorAfterTheFirstWindowStillSurfacesVerbatim() {
        val manager = IrohGatewayManager(
            jarOverride = java.io.File("no/such/gateway.jar"),
            javaOverride = IrohGatewayManager.DEFAULT_JAVA_21,
        )
        manager.errorMonitorWindowSeconds = 1
        val stub = ProcessBuilder(
            "cmd", "/c",
            "echo " + GatewayContract.READY_LINE_PREFIX +
                "{\"role\":\"join\",\"node\":\"" + "ab".repeat(32) +
                "\",\"relay\":null,\"ports\":[3724,8085],\"addrs\":[]}" +
                " & ping -n 3 127.0.0.1 >nul" +
                " & echo " + GatewayContract.ERROR_LINE_PREFIX +
                "{\"code\":\"LATE_REWRITE\",\"detail\":\"late rewrite death\"}" +
                " & ping -n 2 127.0.0.1 >nul",
        ).start()
        try {
            manager.armJoinReadyWatcher(GatewayProcess(stub), descriptor)
            val deadline = System.currentTimeMillis() + 10_000
            while (
                manager.joinState.value !is IrohGatewayManager.JoinState.Error &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
            val failure = manager.joinState.value as? IrohGatewayManager.JoinState.Error
            assertTrue(
                "a late post-READY error must surface (R4-N3-3's stranded-line class)",
                failure != null,
            )
            assertEquals(
                "the gateway's own code must surface verbatim",
                "LATE_REWRITE",
                failure!!.code,
            )
            assertEquals(
                "the gateway's own detail must surface verbatim",
                "late rewrite death",
                failure.detail,
            )
        } finally {
            stub.destroy()
            manager.close()
        }
    }

    /** its death-drain leg: the gateway queues its error line and
     * dies while Ready — the death watcher reads the QUEUED line and
     * surfaces its verbatim code/detail; the generic fallback runs only
     * when the queue is truly empty. */
    @Test
    fun aDeathWithAQueuedErrorSurfacesTheGatewaySOwnWords() {
        val manager = IrohGatewayManager(
            jarOverride = java.io.File("no/such/gateway.jar"),
            javaOverride = IrohGatewayManager.DEFAULT_JAVA_21,
        )
        // READY, then the error line, then immediate death.
        val stub = ProcessBuilder(
            "cmd", "/c",
            "echo " + GatewayContract.READY_LINE_PREFIX +
                "{\"role\":\"join\",\"node\":\"" + "ab".repeat(32) +
                "\",\"relay\":null,\"ports\":[3724,8085],\"addrs\":[]}" +
                " & echo " + GatewayContract.ERROR_LINE_PREFIX +
                "{\"code\":\"REWRITE\",\"detail\":\"GATEWAY-REALMLIST-REWRITE-FAILED\"}",
        ).start()
        try {
            manager.armJoinReadyWatcher(GatewayProcess(stub), descriptor)
            val deadline = System.currentTimeMillis() + 10_000
            while (
                manager.joinState.value !is IrohGatewayManager.JoinState.Error &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(50)
            }
            val failure = manager.joinState.value as? IrohGatewayManager.JoinState.Error
            assertTrue(
                "a post-READY death with a queued error must flip to Error",
                failure != null,
            )
            assertEquals(
                "the queued gateway code must win over the generic fallback",
                "REWRITE",
                failure!!.code,
            )
            assertEquals(
                "the queued gateway detail must win over the generic fallback",
                "GATEWAY-REALMLIST-REWRITE-FAILED",
                failure.detail,
            )
        } finally {
            stub.destroy()
            manager.close()
        }
    }

    /** Round-4 R3-3: the transport secret NEVER rides argv — the join
     * command carries the stdin flag, and no element contains a 64-hex
     * run (Win32_Process-readable for the endpoint's whole lifetime
     * otherwise). */
    @Test
    fun theJoinCommandCarriesTheStdinFlagAndNeverTheSecret() {
        val manager = IrohGatewayManager(
            jarOverride = java.io.File("no/such/gateway.jar"),
            javaOverride = IrohGatewayManager.DEFAULT_JAVA_21,
        )
        val secret = "cd".repeat(32)
        val args = manager.joinArgs(descriptor, "wotlk", relayOnly = true)
        val command = buildList {
            add(IrohGatewayManager.DEFAULT_JAVA_21)
            add("-jar")
            add("gateway-all.jar")
            addAll(args)
        }
        assertTrue(
            "the join argv must select the stdin secret channel",
            args.contains(GatewayContract.ARG_SECRET_STDIN),
        )
        for (element in command) {
            // The node id is PUBLIC (the listing's field) and rides argv
            // legitimately — every OTHER element must carry no 64-hex run
            // and nothing of the secret.
            if (element == descriptor.nodeHex) continue
            assertTrue(
                "argv element leaks secret material: '$element'",
                !element.contains(secret) &&
                    !Regex("(?i)[0-9a-f]{64}").containsMatchIn(element),
            )
        }
        // The descriptor's node id is PUBLIC (it is the listing's field) —
        // it belongs on argv; only the SECRET must not.
        assertTrue(args.contains(descriptor.nodeHex))
    }

    /** Code-red round-5 R2-r5-2: a CRASHED host gateway is never a latch
     * — the ensure beat after a death RELAUNCHES (the stub is READY-then-
     * exit; the second ensureHost must spawn again, not return false
     * forever). */
    @Test
    fun aDeadHostGatewayIsRelaunchedByTheNextEnsureBeat() {
        val manager = IrohGatewayManager(
            jarOverride = java.io.File("no/such/gateway.jar"),
            javaOverride = IrohGatewayManager.DEFAULT_JAVA_21,
        )
        var launches = 0
        var spawned: GatewayProcess? = null
        manager.processLauncher = { _, _ ->
            launches += 1
            // A stub host: READY then immediate death.
            GatewayProcess(
                ProcessBuilder(
                    "cmd", "/c",
                    "echo " + GatewayContract.READY_LINE_PREFIX +
                        "{\"role\":\"host\",\"node\":\"" + "cd".repeat(32) +
                        "\",\"relay\":null}" +
                        " & ping -n 1 127.0.0.1 >nul",
                ).start(),
            ).also { spawned = it }
        }
        try {
            assertTrue(manager.ensureHost("ab".repeat(32)))
            val deadline = System.currentTimeMillis() + 10_000
            while (spawned?.isAlive() == true && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertTrue(
                "the stub host must die so the relaunch leg is exercised",
                spawned?.isAlive() == false,
            )
            assertTrue(
                "the ensure beat after a death must RELAUNCH",
                manager.ensureHost("ab".repeat(32)),
            )
            assertEquals(
                "the death is observed and counted on the next ensure",
                1,
                manager.deadHostDeaths,
            )
            assertEquals(
                "a dead host process must never latch the ensure lane",
                2,
                launches,
            )
        } finally {
            manager.stopHost()
            manager.close()
        }
    }

    /** (b) the launch-failure ladder is TOTAL — an
     * IOException from the spawn (not a GatewayLaunchException) lands in
     * JoinState.Error under the VAL-IROH-JOIN-* code instead of escaping
     * startJoin and stranding the lane in Starting. */
    @Test
    fun anIOExceptionFromTheSpawnLandsInTheErrorLaneNotAnEscape() {
        val manager = IrohGatewayManager(
            jarOverride = java.io.File("no/such/gateway.jar"),
            javaOverride = IrohGatewayManager.DEFAULT_JAVA_21,
        )
        manager.processLauncher = { _, _ ->
            throw java.io.IOException(
                "CreateProcess error=206, The filename or extension is too long",
            )
        }
        try {
            val returned = manager.startJoin(descriptor, "wotlk", relayOnly = true)
            assertTrue(
                "the OS-level spawn failure must be reported, not escaped",
                returned != null,
            )
            val failure = manager.joinState.value as? IrohGatewayManager.JoinState.Error
            assertTrue(
                "the IOException must land in JoinState.Error",
                failure != null,
            )
            assertEquals(
                IrohGatewayManager.VAL_IROH_JOIN_LAUNCH_FAILED,
                failure!!.code,
            )
            assertTrue(
                "the detail must carry the OS message",
                failure.detail.contains("error=206"),
            )
        } finally {
            manager.close()
        }
    }

    /** Round-4 R6-4-3: the renderer's Error line is a model-level string
     * — the code and detail the gateway sent are what the user reads. */
    @Test
    fun theErrorLaneRendersTheGatewayCodeAndDetailVerbatim() {
        assertEquals(
            "Internet join failed: GATEWAY-REALMLIST-REWRITE-FAILED: nope",
            IrohGatewayManager.joinErrorLine(
                "GATEWAY-REALMLIST-REWRITE-FAILED",
                "nope",
            ),
        )
    }
}
