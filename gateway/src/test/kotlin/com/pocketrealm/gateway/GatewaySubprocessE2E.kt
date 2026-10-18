package com.pocketrealm.gateway

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.discovery.iroh.GatewayContract
import com.pocketrealm.discovery.iroh.RealmListRewrite
import org.junit.jupiter.api.Test
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * W6-N3: the two-process loopback join E2E on the desktop lane — the ask's
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
 * shape (a relay leg needs a second network island; the N2 spike measured
 * the relay transport itself, and the relay-only dial SHAPE is pinned at
 * the args/options level here). The host-side fake stack rides the
 * loopback-pinned --realm-target/--world-target overrides because the join
 * gateway's own listeners own the real 3724/8085 on this machine.
 */
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

                    // 3) The client lane: CMSG_REALM_LIST through the tunnel.
                    val rewritten = realmListExchange()
                    // THE ask's assertion: the rewritten list is EXACTLY
                    // what the client would dial — the joiner's own loopback
                    // world target, never the host's advertised address.
                    val parsed = RealmListRewrite.parse(rewritten, ClientFlavors.WOTLK)
                    assertEquals(1, parsed.entries.size)
                    assertEquals(
                        RealmListRewrite.JOINER_WORLD_TARGET,
                        parsed.entries[0].address,
                    )
                    val text = String(rewritten, Charsets.US_ASCII)
                    assertTrue(text.contains("127.0.0.1:8085"))
                    assertTrue(
                        !text.contains("198.51.100.23"),
                        "the host's advertised address must never reach the client",
                    )

                    // 4) The world lane: raw bytes round-trip (no framing).
                    worldEchoExchange()
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

    // ------------------------------------------------------- client lanes

    /** Dial the join gateway's 3724, request the realm list, read ONE
     * SMSG frame back (the rewritten one). */
    private fun realmListExchange(): ByteArray {
        Socket("127.0.0.1", GatewayContract.PORT_REALM).use { client ->
            client.tcpNoDelay = true
            // The client leg of the REAL auth dance (code-red R5-1):
            // challenge request → raw challenge response, proof request
            // → raw proof response, and only then CMSG_REALM_LIST.
            val output = client.getOutputStream()
            // The request sizes match the fake realmd's reads (the raw
            // real shapes are variable; the fake pins 75/75).
            val challengeReq = ByteArray(75).also { it[0] = 0x00 }
            output.write(challengeReq)
            output.flush()
            val authInput = DataInputStream(client.getInputStream())
            authInput.readFully(ByteArray(118)) // the raw challenge response
            val proofReq = ByteArray(75).also { it[0] = 0x01 }
            output.write(proofReq)
            output.flush()
            authInput.readFully(ByteArray(32)) // the raw proof response
            // CMSG_REALM_LIST: opcode 0x10 + u16 size 4 + 4 bytes (the
            // realmd handler ReadSkip(4)s them).
            val request = byteArrayOf(0x10, 0x00, 0x04, 1, 2, 3, 4)
            output.write(request)
            client.getOutputStream().flush()
            val input = authInput
            val opcode = input.readUnsignedByte()
            // The realmd size field is u16 LITTLE-ENDIAN.
            val size = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
            assertEquals(
                RealmListRewrite.CMD_REALM_LIST, opcode,
                "the reply must be the realm list",
            )
            val payload = ByteArray(size)
            input.readFully(payload)
            val frame = byteArrayOf(opcode.toByte(), size.toByte(), (size shr 8).toByte()) + payload
            // Payload capture for the privacy grep (a commit-time fixture:
            // the pytest greps THESE bytes — the real wire payloads the
            // two-process join produced).
            dumpCapturedPayload("realm-list-request.bin", request)
            dumpCapturedPayload("realm-list-rewritten.bin", frame)
            return frame
        }
    }

    /** Writes one captured wire payload when -Dpocket.payload.dump names a
     * directory (the E2E evidence run); a no-op otherwise. */
    private fun dumpCapturedPayload(name: String, bytes: ByteArray) {
        val dir = System.getProperty("pocket.payload.dump") ?: return
        java.io.File(dir).mkdirs()
        java.io.File(dir, name).writeBytes(bytes)
    }

    /** Dial the join gateway's 8085 and round-trip a payload through the
     * fake world's echo (raw bytes, no framing — the mux header is the
     * join gateway's own port-id prefix on the iroh side). */
    private fun worldEchoExchange() {
        Socket("127.0.0.1", GatewayContract.PORT_WORLD).use { client ->
            client.tcpNoDelay = true
            val payload = ByteArray(1024) { (it % 251).toByte() }
            client.getOutputStream().write(payload)
            client.getOutputStream().flush()
            val echo = ByteArray(payload.size)
            val input = DataInputStream(client.getInputStream())
            input.readFully(echo)
            assertTrue(
                payload.contentEquals(echo),
                "the world lane must tunnel byte-exactly",
            )
        }
    }

    // -------------------------------------------------------- fake stack

    /** The fake realmd: reads client frames; answers a CMSG_REALM_LIST
     * (opcode 0x10) with the pinned fixture (the host's raw address —
     * what realmd would send before a rewrite). */
    private class FakeRealmServer(port: Int, private val worldPort: Int) : AutoCloseable {
        private val server = ServerSocket()
        @Volatile private var stopped = false
        private val threads = mutableListOf<Thread>()

        init {
            server.bind(InetSocketAddress("127.0.0.1", port), 8)
            val thread = Thread({
                while (!stopped) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    threads += Thread({
                        try {
                            client.tcpNoDelay = true
                            val input = DataInputStream(client.getInputStream())
                            val output = client.getOutputStream()
                            // The REAL auth dance first (code-red R5-1):
                            // the logon challenge/proof exchanges are RAW
                            // structs; the realm list only flows once the
                            // lane reaches its framed phase.
                            output.write(challengeResponse()); output.flush()
                            input.readFully(ByteArray(75)) // the client's challenge request
                            output.write(proofResponse()); output.flush()
                            input.readFully(ByteArray(75)) // the client's proof request
                            while (true) {
                                val opcode = try {
                                    input.readUnsignedByte()
                                } catch (_: EOFException) {
                                    break
                                }
                                val size = input.readUnsignedShort()
                                input.readFully(ByteArray(size))
                                if (opcode == RealmListRewrite.CMD_REALM_LIST) {
                                    output.write(fixtureFor(size))
                                    output.flush()
                                }
                            }
                        } catch (_: Exception) {
                            // flow ended
                        } finally {
                            runCatching { client.close() }
                        }
                    }, "fake-realmd-flow")
                        .apply { isDaemon = true }
                        .also { it.start() }
                }
            }, "fake-realmd-accept")
            thread.isDaemon = true
            thread.start()
            threads += thread
        }

        /** The RAW logon-challenge response (AuthSocket.cpp:489-543) —
         * the filter's CHALLENGE phase consumes exactly this. */
        private fun challengeResponse(): ByteArray {
            val out = ArrayList<Byte>()
            out.add(0x00); out.add(0x00) // cmd, err = SUCCESS
            repeat(32) { out.add(((it * 31 + 7) and 0xff).toByte()) } // B
            out.add(0x01); out.add(0x07) // g_len=1, g=7
            out.add(32)
            repeat(32) { out.add(((it * 17 + 3) and 0xff).toByte()) } // N
            repeat(32) { out.add(((it * 5 + 11) and 0xff).toByte()) } // s
            repeat(16) { out.add(((it * 13 + 1) and 0xff).toByte()) } // VersionChallenge
            out.add(0x00) // securityFlags = NONE
            return out.toByteArray()
        }

        /** The RAW logon-proof response (AuthSocket.cpp:135-143, the
         * 2.x/3.x layout) — the filter's PROOF phase consumes exactly
         * this. */
        private fun proofResponse(): ByteArray {
            val out = ArrayList<Byte>()
            out.add(0x01); out.add(0x00) // cmd, err = SUCCESS
            repeat(20) { out.add(((it * 7 + 5) and 0xff).toByte()) } // M2
            repeat(8) { out.add(0) } // accountFlags + surveyId
            out.add(0); out.add(0) // unkFlags
            return out.toByteArray()
        }

        /** The fixture's world address re-pointed at THIS fake stack (the
         * host gateway dials the alt ports, so the fixture's C-string must
         * name them for the world-lane hop to be real). Numeric fields
         * LITTLE-ENDIAN per the RealmListRewrite endianness doc. */
        private fun fixtureFor(@Suppress("UNUSED_PARAMETER") realmListRequestSize: Int): ByteArray {
            // Rebuild the pinned fixture with the fake world's port: the
            // name/build stay pinned; the address carries this stack's port.
            val name = "Pocket Realm".toByteArray(Charsets.US_ASCII) + 0
            val address = "198.51.100.23:$worldPort".toByteArray(Charsets.US_ASCII) + 0
            val payload = byteArrayOf(
                0, 0, 0, 0, // u32 LE header word
                1, 0, // u16 LE count
                0, 0, // u8 icon, u8 lock
                0x24, // flags: SPECIFYBUILD
            ) + name + address + byteArrayOf(
                0x00, 0x00, 0x00, 0x3f, // f32 LE population 0.5
                3, 1, 0x2c, // chars, category, unk
                3, 3, 5, // build block major.minor.bugfix
                0x34, 0x30, // u16 LE build (the wotlk registry build)
                0x10, 0x00, // u16 LE trailer 0x0010
            )
            return byteArrayOf(
                RealmListRewrite.CMD_REALM_LIST.toByte(),
                (payload.size and 0xff).toByte(),
                ((payload.size shr 8) and 0xff).toByte(), // u16 LE size
            ) + payload
        }

        override fun close() {
            stopped = true
            runCatching { server.close() }
        }
    }

    /** The fake mangosd world: echoes every byte back (raw lane). */
    private class FakeEchoServer(port: Int) : AutoCloseable {
        private val server = ServerSocket()
        @Volatile private var stopped = false

        init {
            server.bind(InetSocketAddress("127.0.0.1", port), 8)
            Thread({
                while (!stopped) {
                    val client = try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                    Thread({
                        try {
                            client.tcpNoDelay = true
                            val buffer = ByteArray(16 * 1024)
                            val input = client.getInputStream()
                            val output = client.getOutputStream()
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (n > 0) {
                                    output.write(buffer, 0, n)
                                    output.flush()
                                }
                            }
                        } catch (_: Exception) {
                            // flow ended
                        } finally {
                            runCatching { client.close() }
                        }
                    }, "fake-world-flow")
                        .apply { isDaemon = true }
                        .start()
                }
            }, "fake-world-accept")
                .apply { isDaemon = true }
                .start()
        }

        override fun close() {
            stopped = true
            runCatching { server.close() }
        }
    }

    // --------------------------------------------------- process plumbing

    private data class ReadyLine(
        val node: String,
        val relay: String?,
        val portsList: List<Int>,
        val boundSockets: List<String>,
    ) {
        /** The mux's realm lane (the JOIN gateway binds these). */
        val ports: Int get() = portsList.first()

        /** The iroh endpoint's actual UDP socket (the DIRECT lane's dial
         * input — NOT the mux port). The endpoint binds the wildcard, so
         * the one-machine loopback E2E re-points the dial at 127.0.0.1 on
         * the same port. */
        val dialSocket: String
            get() {
                val raw = boundSockets.firstOrNull()
                    ?: error("the host READY carried no bound socket to dial")
                val port = raw.substringAfterLast(':')
                return "127.0.0.1:$port"
            }
    }

    private class GatewayProc(private val process: Process) : AutoCloseable {
        private val stdout = java.util.concurrent.LinkedBlockingQueue<String>()

        /** Whether the process is still running (the loss watcher's exit
         * leg reads this). */
        fun isAlive(): Boolean = process.isAlive

        /** Whether any PR_GATEWAY_ERROR line has arrived (the loss
         * watcher's error-line leg). */
        fun sawErrorLine(): Boolean = stdout.any {
            it.startsWith(GatewayContract.ERROR_LINE_PREFIX)
        }
        private val drain = Thread({
            process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                stdout.add(line)
            }
        }).apply { isDaemon = true; start() }
        private val stderrTail = java.util.concurrent.LinkedBlockingQueue<String>()

        init {
            Thread({
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    stderrTail.add(line)
                    if (stderrTail.size > 20) stderrTail.poll()
                }
            }).apply { isDaemon = true; start() }
        }

        /** Waits (bounded) for the READY line; throws with the process's
         * error line (or exit code + stderr tail) — never a silent hang. */
        fun awaitReadyOrThrow(timeoutMs: Long = 45_000): ReadyLine {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                stdout.poll()?.let { line ->
                    if (line.startsWith(GatewayContract.READY_LINE_PREFIX)) {
                        return parseReady(line.removePrefix(GatewayContract.READY_LINE_PREFIX))
                    }
                    // an early ERROR line is the answer
                    if (line.startsWith(GatewayContract.ERROR_LINE_PREFIX)) {
                        throw AssertionError(
                            "gateway errored before READY: " +
                                line.removePrefix(GatewayContract.ERROR_LINE_PREFIX),
                        )
                    }
                }
                if (!process.isAlive) {
                    throw AssertionError(
                        "gateway exited (code ${process.exitValue()}) before READY; " +
                            "stderr: ${stderrTail.joinToString(" | ")}",
                    )
                }
                Thread.sleep(50)
            }
            throw AssertionError("gateway never became READY in ${timeoutMs}ms")
        }

        /** Waits (bounded) for the named ERROR line (the loud-failure path). */
        fun awaitErrorOrThrow(code: String, timeoutMs: Long = 20_000) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                stdout.poll()?.let { line ->
                    if (line.startsWith(GatewayContract.ERROR_LINE_PREFIX)) {
                        val payload = line.removePrefix(GatewayContract.ERROR_LINE_PREFIX)
                        assertTrue(
                            payload.contains(code),
                            "expected error $code, got: $payload",
                        )
                        return
                    }
                }
                if (!process.isAlive) {
                    throw AssertionError(
                        "gateway exited (code ${process.exitValue()}) without the " +
                            "expected $code line; stderr: ${stderrTail.joinToString(" | ")}",
                    )
                }
                Thread.sleep(50)
            }
            throw AssertionError("gateway never emitted $code in ${timeoutMs}ms")
        }

        private fun parseReady(json: String): ReadyLine {
            val node = Regex("\"node\":\"([0-9a-f]{64})\"").find(json)?.groupValues?.get(1)
                ?: throw AssertionError("READY without a node id: $json")
            val relay = Regex("\"relay\":\"([^\"]*)\"").find(json)?.groupValues?.get(1)
            val ports = Regex("\"ports\":\\[([0-9,]+)\\]").find(json)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim().toInt() }
                ?: throw AssertionError("READY without ports: $json")
            val addrs = Regex("\"addrs\":\\[([^\\]]*)\\]").find(json)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() }
                ?: emptyList()
            return ReadyLine(node, relay?.takeIf { it.isNotEmpty() }, ports, addrs)
        }

        fun stop() {
            process.destroy()
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                process.destroyForcibly()
            }
        }

        override fun close() = stop()
    }

    private fun launchGateway(vararg args: String): GatewayProc {
        val javaBin = Path(
            System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
        )
        val jar = locateFatJar()
        val command = mutableListOf(
            javaBin.toString(),
            "-Djna.tmpdir=E:/pocket_realm_toolchain/tmp/jna",
            "-jar", jar,
        )
        command += args.toList()
        return GatewayProc(ProcessBuilder(command).start())
    }

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i ->
            ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte()
        }

    companion object {
        private val SECRET_HOST = "11".repeat(32)
        private val SECRET_JOIN = "22".repeat(32)
        private val SECRET_JOIN2 = "33".repeat(32)
    }
}

/** The CURRENT fat jar (the test task dependsOn fatJar) and a free
 * loopback port — shared by the E2E and the args-gate pins. */
private fun locateFatJar(): String {
    var dir = Path(System.getProperty("user.dir")).toAbsolutePath()
    repeat(4) {
        val libs = dir.resolve("gateway").resolve("build").resolve("libs")
        if (libs.toFile().isDirectory) {
            val jar = libs.toFile().listFiles { f ->
                f.name.startsWith("gateway-") && f.name.endsWith("-all.jar")
            }?.maxByOrNull { it.lastModified() }
            if (jar != null) return jar.absolutePath
        }
        dir = dir.parent ?: return@repeat
    }
    throw AssertionError("the gateway fat jar was not found — run the fatJar task first")
}

private fun freeLoopbackPort(): Int =
    ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

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
