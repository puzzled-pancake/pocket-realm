package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.GatewayContract
import com.pocketrealm.discovery.iroh.IrohJoinDescriptor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W6-N4 — the reconnect proof's GENUINE network-identity-change tier
 * (docker-isolated; the ask's "desktop lane with two interfaces" shape as
 * far as this desktop honestly allows).
 *
 * What makes this a REAL network change and not a process re-dial: the
 * HOST gateway process NEVER RESTARTS. It runs in a docker container on
 * isolated bridge A; mid-proof the container is moved to isolated bridge
 * B (`docker network disconnect` + `connect`), so the host endpoint's
 * source IP genuinely changes (172.30.x → 172.31.x) under a live iroh
 * endpoint — the closest a non-elevated desktop gets to pulling and
 * re-plugging a network cable. The joiner (its own container on bridge B;
 * inter-bridge traffic is docker-isolated, so NO direct path exists — the
 * N2 relay-leg's isolation basis, D92 §2) holds the ORIGINAL listing
 * descriptor the whole time, and the client lane runs INSIDE the joiner
 * container via `docker exec` (the joiner binds 127.0.0.1-only — the
 * production privacy shape a host-side port mapping can never reach).
 *
 * Outcomes, both honest and both acceptable to the ask:
 *  - "endpoint-survived": the host's iroh endpoint migrates/recovers its
 *    relay registration transparently (QUIC path migration) and the
 *    ORIGINAL tunnel never drops — recorded as the stronger result, and
 *    the redial is still proven by a deliberate joiner stop + re-join on
 *    the same descriptor.
 *  - "loss-redial": the tunnel dies with the path (the keep-alive lane
 *    fires LOUD, the join process exits DIAL_FAILED) and the redialed
 *    joiner reconnects to the SAME endpoint id on the SAME descriptor.
 *  - fallback "host-restarted": only if the live endpoint wedges (never
 *    accepts again) — the host container is restarted on the SAME realm
 *    key; the same-endpoint-id pin then carries the proof. Recorded as
 *    the weakest outcome, never silently.
 *
 * EVIDENCE DURABILITY (code-red round 1, R1-1/R1-2/R4-1/R5-1/R6-N4-r1-2/
 * R7-N4-1/R7-N4-2): the gradle console does not capture test stdout, so
 * the proof's headline lines are archived FIRST-CLASS — every N4-DOCKER
 * narration line (the before/after host IPs, the outcome) is written to
 * the wave evidence dir at teardown, and teardown collects each
 * container's `docker logs` BEFORE removing it (the redialed joiner's
 * PR_GATEWAY_READY line is the reconnect leg's own receipt). The wave
 * exit cites the narration file, never the console log.
 *
 * A physical hotspot/CGNAT leg stays owner-scheduled for W7 (D92 §2);
 * D98 records this run as the desktop approximation it is.
 */
@EnabledIfSystemProperty(named = "pocket.reconnect.docker", matches = "true")
class ReconnectDockerE2E {

    private val suffix = System.currentTimeMillis().toString(36)
    private var netA: String? = null
    private var netB: String? = null

    /** EVERY host container this run created (the fallback restart adds a
     * second one) — teardown logs-then-removes each of them. */
    private val hostgws = ArrayList<String>()

    /** Every joiner container this run created. */
    private val joingws = ArrayList<String>()

    /** The proof's narration: the exact lines the wave exit cites (host
     * IPs before/after, the outcome, the reconnect receipt). Printed AND
     * archived at teardown (R1-1: the console alone is not evidence). */
    private val narration = ArrayList<String>()

    private var secretsDir: File? = null

    // ------------------------------------------------------------- test

    @Test
    fun hostSourceNetworkChangeSurvivedByRedialingTheSameEndpointId() {
        preflightDockerOrFail()
        try {
            setUpNetworks()
            val secrets = File(waveEvidenceDir(), "secrets-$suffix")
            secrets.mkdirs()
            secretsDir = secrets
            val hostSecret = freshHexSecret()
            writeSecret("host", hostSecret)

            // 1) The HOST gateway container on bridge A (fake stack in
            //    its namespace; default relay mode → home relay N0).
            val host1 = "pr-n4-$suffix-hostgw"
            hostgws.add(host1)
            runGatewayContainer(
                host1, netA!!, withFakeStack = true, secretFile = "host",
            )
            val hostIpBefore = containerIp(host1)
            val ready1 = awaitContainerReady(host1, "hostgw run #1", HOST_READY_BUDGET_MS)
            val d1 = IrohJoinDescriptor(nodeHex = ready1.node, relayUrl = ready1.relay!!)
            note("host READY node=${d1.nodeHex.take(12)}… relay=${d1.relayUrl} ip=$hostIpBefore")

            // 2) The joiner container on bridge B (relay-only: the
            //    listing's home relay and NOTHING else). The client lane
            //    runs INSIDE the joiner (the joiner binds loopback-only;
            //    the production shape).
            val join1 = startJoiner("join1", d1)
            awaitContainerReady(join1, "joingw #1", JOIN_READY_BUDGET_MS)
            exchangeInJoiner(join1)
            note("pre-change tunnel verified (both lanes) via the public relay")

            // 3) THE HOST NETWORK IDENTITY CHANGE: bridge A → bridge B
            //    under the LIVE host endpoint (process never restarts).
            dockerOrThrow("network", "disconnect", netA!!, host1)
            val midChange = containerIp(host1)
            assertEquals(
                NO_ADDRESS, midChange,
                "the container must be networkless mid-change",
            )
            Thread.sleep(2_000) // the outage window (the cable is pulled)
            dockerOrThrow("network", "connect", netB!!, host1)
            val hostIpAfter = containerIp(host1)
            assertTrue(
                hostIpAfter != hostIpBefore && hostIpAfter != NO_ADDRESS,
                "the host's source network must have changed ($hostIpBefore → $hostIpAfter)",
            )
            assertEquals(
                "true", containerField(host1, ".State.Running"),
                "the host endpoint must still be RUNNING after the network change",
            )
            note("host network changed $hostIpBefore -> $hostIpAfter (process never restarted)")

            // 4) EITHER the original tunnel dies loudly (loss-redial) OR
            //    it survives transparently (endpoint-survived). Both are
            //    honest; both are recorded.
            val lossDeadline = System.currentTimeMillis() + LOSS_WINDOW_MS
            var originalJoinSurvived = true
            while (System.currentTimeMillis() < lossDeadline) {
                if (logs(join1).contains("connection lost") || !containerRunning(join1)) {
                    originalJoinSurvived = false
                    break
                }
                Thread.sleep(500)
            }
            if (originalJoinSurvived) {
                // The endpoint migrated transparently: the ORIGINAL join
                // must still carry bytes.
                exchangeInJoiner(join1)
                note("OUTCOME=endpoint-survived (original tunnel never dropped)")
                // join1 stays up (its tunnel is live); the redial
                // property is still proven the honest way below — a
                // deliberate fresh join on the SAME descriptor. No
                // removal here: teardown owns log-then-remove (R1-2).
            } else {
                note("OUTCOME=loss-redial (the keep-alive lane fired)")
                val exit = awaitContainerExit(join1, 30_000)
                assertEquals(
                    GatewayContract.ExitCode.DIAL_FAILED, exit,
                    "the connection-loss exit must be the loud DIAL_FAILED lane",
                )
            }
            assertEquals(
                "true", containerField(host1, ".State.Running"),
                "the host endpoint process must never have restarted in this leg",
            )

            // 5) The joiner REDIALS the ORIGINAL descriptor (fresh join
            //    identity — the production per-join secret; the LISTING
            //    is unchanged). This must reconnect to the same node id.
            val join2 = startJoiner("join2", d1)
            var outcome = if (originalJoinSurvived) "endpoint-survived" else "loss-redial"
            var redialed = runCatching {
                awaitContainerReady(join2, "joingw #2 redial", REDIAL_BUDGET_MS)
            }
            if (redialed.isFailure) {
                // Fallback ONLY for a wedged live endpoint: restart the
                // host container on the SAME realm key — the identity
                // pin carries the proof. Recorded, never silent.
                note(
                    "the live endpoint did not answer the redial in " +
                        "${REDIAL_BUDGET_MS}ms — falling back to the same-key host restart",
                )
                val host2 = "pr-n4-$suffix-hostgw2"
                hostgws.add(host2)
                runGatewayContainer(
                    host2, netB!!, withFakeStack = true, secretFile = "host",
                )
                val readyRestart = awaitContainerReady(host2, "hostgw restart (same key)", HOST_READY_BUDGET_MS)
                assertEquals(
                    d1.nodeHex, readyRestart.node,
                    "the restarted host (SAME realm key) must bind the SAME endpoint id",
                )
                assertEquals(
                    d1.relayUrl, readyRestart.relay,
                    "the restarted host must land on the SAME home relay (no new listing)",
                )
                outcome = "host-restarted"
                redialed = runCatching {
                    awaitContainerReady(join2, "joingw #2 after restart", REDIAL_BUDGET_MS)
                }
            }
            assertTrue(
                redialed.isSuccess,
                "the redialed joiner must reach the SAME endpoint id on the " +
                    "ORIGINAL descriptor; logs: ${logs(join2).takeLast(2000)}",
            )
            exchangeInJoiner(join2)
            note(
                "OUTCOME=$outcome — reconnect WORKING (both lanes byte-exact, " +
                    "same node id, same listing)",
            )
        } finally {
            teardown()
        }
    }

    // ---------------------------------------------------------- helpers

    /** Prints AND records a proof line (printed for the live console,
     * recorded for the archived narration — the wave exit cites the
     * ARCHIVED file, never the console; R1-1/R6-N4-r1-2). */
    private fun note(line: String) {
        narration.add(line)
        println("N4-DOCKER: $line")
    }

    /** The host-gateway container: the fake stack (when asked) in the
     * background, then `exec` the gateway as the container's main
     * process; the transport secret rides the mounted read-only file,
     * never argv (D95/D96 posture, container flavor). */
    private fun runGatewayContainer(
        name: String,
        network: String,
        withFakeStack: Boolean,
        secretFile: String,
    ) {
        val fakeStack = if (withFakeStack) {
            "java -Djna.tmpdir=/tmp/jna -cp /app/$gatewayJarName:/app/$fakeStackJarName " +
                "com.pocketrealm.gateway.FakeStackMain " +
                "--realm-port $FAKE_REALM_PORT --world-port $FAKE_WORLD_PORT & "
        } else {
            ""
        }
        val payload =
            fakeStack +
                "exec java -Djna.tmpdir=/tmp/jna -jar /app/$gatewayJarName " +
                "--mode host --secret-from-stdin < /run/pr-secrets/$secretFile " +
                "--realm-target 127.0.0.1:$FAKE_REALM_PORT " +
                "--world-target 127.0.0.1:$FAKE_WORLD_PORT"
        runContainer(name, network, extraArgs = listOf(), command = listOf("sh", "-c", payload))
    }

    private fun startJoiner(
        tag: String,
        d: IrohJoinDescriptor,
    ): String {
        writeSecret(tag, freshHexSecret()) // the production per-join fresh identity
        val name = "pr-n4-$suffix-$tag"
        joingws.add(name)
        // The `<` redirect is the CONTAINER shell's job — the secret
        // never rides argv. NO port mappings: the joiner binds
        // 127.0.0.1-only inside its container (the production privacy
        // shape), so the client lane runs INSIDE via docker exec.
        val payload =
            "exec java -Djna.tmpdir=/tmp/jna -jar /app/$gatewayJarName " +
                "--mode join --secret-from-stdin < /run/pr-secrets/$tag " +
                "--node ${d.nodeHex} --relay-only --relay '${d.relayUrl}' " +
                "--flavor wotlk"
        runContainer(
            name, netB!!,
            extraArgs = listOf(),
            command = listOf("sh", "-c", payload),
        )
        return name
    }

    private fun runContainer(
        name: String,
        network: String,
        extraArgs: List<String>,
        command: List<String>,
    ) {
        val libs = File(locateFatJar()).parentFile.absolutePath.replace('\\', '/')
        val secrets = secretsDir!!.absolutePath.replace('\\', '/')
        dockerOrThrow(
            "run", "-d", "--name", name, "--network", network,
            "-v", "$libs:/app:ro",
            "-v", "$secrets:/run/pr-secrets:ro",
            *extraArgs.toTypedArray(),
            IMAGE, *command.toTypedArray(),
        )
    }

    /** Runs the asserting client exchange INSIDE the joiner container
     * (dialing its own loopback listeners — the production shape) and
     * asserts the OK line. */
    private fun exchangeInJoiner(joiner: String) {
        val result = runDocker(
            "exec", joiner,
            "java", "-Djna.tmpdir=/tmp/jna",
            "-cp", "/app/$gatewayJarName:/app/$fakeStackJarName",
            "com.pocketrealm.gateway.GatewayClientMain",
            "--realm-port", GatewayContract.PORT_REALM.toString(),
            "--world-port", GatewayContract.PORT_WORLD.toString(),
        )
        assertTrue(
            result.exitCode == 0 && result.output.contains("CLIENT_EXCHANGE_OK"),
            "the client exchange inside $joiner failed " +
                "(exit ${result.exitCode}): ${result.output.take(1200)}",
        )
        note("client exchange inside $joiner: CLIENT_EXCHANGE_OK (both lanes byte-exact)")
    }

    /** Waits for the container's PR_GATEWAY_READY line. */
    private fun awaitContainerReady(name: String, what: String, timeoutMs: Long): ReadyLine {
        val deadline = System.currentTimeMillis() + timeoutMs
        var sawError: String? = null
        while (System.currentTimeMillis() < deadline) {
            val out = logs(name)
            out.lineSequence()
                .firstOrNull { it.startsWith(GatewayContract.ERROR_LINE_PREFIX) }
                ?.let { if (sawError == null) sawError = it }
            out.lineSequence()
                .lastOrNull { it.startsWith(GatewayContract.READY_LINE_PREFIX) }
                ?.let { return parseReadyJson(it.removePrefix(GatewayContract.READY_LINE_PREFIX)) }
            if (!containerRunning(name)) {
                throw AssertionError(
                    "$what exited before READY (code ${containerField(name, ".State.ExitCode")}); " +
                        "logs tail: ${out.takeLast(1500)}",
                )
            }
            Thread.sleep(500)
        }
        throw AssertionError(
            "$what never became READY in ${timeoutMs}ms" +
                (sawError?.let { "; error line: $it" } ?: "") +
                "; logs tail: ${logs(name).takeLast(1500)}",
        )
    }

    private fun awaitContainerExit(name: String, timeoutMs: Long): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!containerRunning(name)) return containerField(name, ".State.ExitCode").toInt()
            Thread.sleep(300)
        }
        throw AssertionError("container $name did not exit within ${timeoutMs}ms")
    }

    /** The container's current IP (the first network's) — [NO_ADDRESS]
     * when it has none (the mid-change moment). The range form works
     * across all three states; this docker's legacy
     * `.NetworkSettings.IPAddress` field is absent for user-defined
     * networks. */
    private fun containerIp(name: String): String {
        val raw = dockerOrThrow(
            "inspect", "-f",
            "{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}",
            name,
        ).output.trim()
        return raw.split(" ").firstOrNull { it.isNotBlank() } ?: NO_ADDRESS
    }

    private fun containerField(name: String, template: String): String =
        dockerOrThrow("inspect", "-f", "{{$template}}", name).output.trim()
            .removeSurrounding("\"")

    private fun containerRunning(name: String): Boolean =
        containerField(name, ".State.Running") == "true"

    private fun logs(name: String): String =
        runDocker("logs", name).output

    private fun setUpNetworks() {
        val a = "pr-n4-$suffix-a"
        val b = "pr-n4-$suffix-b"
        val candidates = listOf(
            "172.30.0.0/24" to "172.31.0.0/24",
            "172.28.0.0/24" to "172.29.0.0/24",
            "10.201.0.0/24" to "10.202.0.0/24",
        )
        for ((subA, subB) in candidates) {
            val ra = runDocker("network", "create", "--subnet", subA, a)
            val rb = runDocker("network", "create", "--subnet", subB, b)
            if (ra.exitCode == 0 && rb.exitCode == 0) {
                netA = a
                netB = b
                return
            }
            runDocker("network", "rm", a)
            runDocker("network", "rm", b)
        }
        throw AssertionError("no free docker subnet pair could be created (tried 3 candidates)")
    }

    private fun writeSecret(tag: String, hex: String) {
        File(secretsDir!!, tag).writeText(hex + "\n")
    }

    private fun preflightDockerOrFail() {
        val info = runDocker("info", "--format", "ok")
        assertTrue(
            info.exitCode == 0,
            "docker is required for this leg (-Dpocket.reconnect.docker=true was set) " +
                "but `docker info` failed: ${info.output.take(400)}",
        )
    }

    private fun dockerOrThrow(vararg args: String): DockerResult {
        val result = runDocker(*args)
        assertEquals(
            0, result.exitCode,
            "docker ${args.joinToString(" ")} failed: ${result.output.take(400)}",
        )
        return result
    }

    private fun runDocker(vararg args: String): DockerResult {
        val process = ProcessBuilder(listOf("docker") + args.toList()).start()
        val finished = process.waitFor(90, TimeUnit.SECONDS)
        val output = if (finished) {
            process.inputStream.bufferedReader().readText() +
                process.errorStream.bufferedReader().readText()
        } else {
            process.destroyForcibly()
            "<<docker timed out: docker ${args.joinToString(" ")}>>"
        }
        return DockerResult(if (finished) process.exitValue() else -1, output)
    }

    /** ARCHIVE FIRST, REMOVE SECOND (code-red R1-2/R4-1/R5-1/R7-N4-2):
     * every container's `docker logs` is written into the evidence dir
     * BEFORE its removal — the redial leg's own join2 PR_GATEWAY_READY
     * line is the proof's receipt, and a daemon error string in an
     * archived log is a bug, never evidence. The narration file (the
     * lines the wave exit cites) is written here too. */
    private fun teardown() {
        val evidence = secretsDir ?: waveEvidenceDir().apply { mkdirs() }
        evidence.mkdirs()
        for (name in hostgws + joingws) {
            val log = logs(name)
            File(evidence, "$name.log").writeText(log)
            runDocker("rm", "-f", name)
        }
        File(evidence, "n4-docker-narration-$suffix.txt").writeText(
            narration.joinToString(separator = "\n", postfix = "\n"),
        )
        netA?.let { runDocker("network", "rm", it) }
        netB?.let { runDocker("network", "rm", it) }
    }

    private data class DockerResult(val exitCode: Int, val output: String)

    /** The exact jar file names mounted into the containers (the fat
     * jar carries the version in its name). */
    private val gatewayJarName: String = File(locateFatJar()).name
    private val fakeStackJarName: String = File(locateFatJar()).parentFile
        .listFiles { f -> f.name.startsWith("gateway-fakestack") }!!
        .firstOrNull()?.name
        ?: error("gateway-fakestack jar not found — run the fakeStackJar task")

    companion object {
        /** eclipse-temurin:21-jre — the image the N2 spike's docker legs
         * left on this machine (JVM 21 = the gateway artifact's floor). */
        const val IMAGE: String = "eclipse-temurin:21-jre"

        /** The fake stack's ports INSIDE the hostgw container (its own
         * namespace; fixed because the namespace is fresh). */
        const val FAKE_REALM_PORT = 13724
        const val FAKE_WORLD_PORT = 18085

        /** The keep-alive loss window's poll budget (probe ≈ 5 s; QUIC's
         * own death detection can lag the path loss — 45 s of grace). */
        const val LOSS_WINDOW_MS: Long = 45_000

        /** The redial budget (the join dial is 30 s; recovery of the
         * host's relay registration rides on top). */
        const val REDIAL_BUDGET_MS: Long = 75_000

        /** The host container's READY budget (container JVM start + the
         * relay registration). */
        const val HOST_READY_BUDGET_MS: Long = 120_000

        /** The joiner container's READY budget. */
        const val JOIN_READY_BUDGET_MS: Long = 90_000

        /** The render shape of an unset IP (the mid-change moment). */
        const val NO_ADDRESS: String = "<no address>"
    }
}
