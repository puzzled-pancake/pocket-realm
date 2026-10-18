package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.GatewayContract
import com.pocketrealm.discovery.iroh.RealmListRewrite
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.io.path.Path
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * W6-N3 code-red R2 (adversarial breaker): the LOUD rewrite-failure exit —
 * the ask's "fail the join loudly on dial/rewrite failure" — exercised at
 * the PROCESS level. A hostile/misbehaving host's realmd answers
 * CMSG_REALM_LIST with a frame that fails the flavor's structural parse
 * (corrupted trailer); the join gateway must translate that into
 * GATEWAY-REALMLIST-REWRITE-FAILED on stdout and exit
 * REALMLIST-REWRITE-FAILED (4), killing the join — never half-serve a
 * client that would dial the host's raw address.
 *
 * The unit-level loud refusal (AuthStreamFilterTest) proves the FILTER
 * throws; THIS test proves the GATEWAY kills itself when it does — the two
 * are joined only by coroutine scheduling, which is exactly the seam this
 * test exists to pin.
 */
@ExtendWith(GamePortsGuard::class)
class GatewayBreakerR2NegativeE2E {

    @Test
    fun aCorruptRealmListFromTheHostKillsTheJoinLoudly() {
        val fakeRealmPort = freeLoopbackPort()
        CorruptRealmServer(fakeRealmPort).use { fake ->
            val worldPort = freeLoopbackPort()
            val host = launchGateway(
                "--mode", "host",
                "--secret", SECRET_HOST,
                "--relay-disabled",
                "--realm-target", "127.0.0.1:$fakeRealmPort",
                "--world-target", "127.0.0.1:$worldPort",
                "--max-seconds", "60",
            )
            try {
                val hostReady = host.awaitReadyOrThrow()
                val join = launchGateway(
                    "--mode", "join",
                    "--secret", SECRET_JOIN,
                    "--node", hostReady.nodeHex,
                    "--direct-allowed",
                    "--addr", hostReady.dialSocket,
                    "--flavor", "wotlk",
                    "--relay-disabled",
                    "--max-seconds", "30",
                )
                try {
                    join.awaitReadyOrThrow()
                    // The client asks for the realm list; the fake realmd
                    // answers with a structurally corrupt frame.
                    corruptRealmListExchange()
                    // THE CONTRACT: the join process must die loudly with
                    // the rewrite-failure error line and exit code 4.
                    join.awaitRewriteFailureOrDocumentTheBreach()
                } finally {
                    join.stop()
                }
            } finally {
                host.stop()
            }
        }
    }

    // ------------------------------------------------------- client lane

    /** Dial the join gateway's 3724, request the realm list, and record
     * what comes back (the corrupt-frame path must emit NOTHING to the
     * client on the way to the loud exit). Bounded — never a hang. */
    private fun corruptRealmListExchange() {
        Socket("127.0.0.1", GatewayContract.PORT_REALM).use { client ->
            client.tcpNoDelay = true
            client.soTimeout = 8_000
            val output = client.getOutputStream()
            // The client leg of the REAL auth dance (code-red R5-1) so
            // the filter is FRAMED when the corrupt frame arrives.
            output.write(ByteArray(75).also { it[0] = 0x00 })
            output.flush()
            val input = DataInputStream(client.getInputStream())
            input.readFully(ByteArray(118)) // the raw challenge response
            output.write(ByteArray(75).also { it[0] = 0x01 })
            output.flush()
            input.readFully(ByteArray(32)) // the raw proof response
            val request = byteArrayOf(0x10, 0x00, 0x04, 1, 2, 3, 4)
            output.write(request)
            output.flush()
            try {
                val opcode = input.readUnsignedByte()
                val size = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
                // If anything arrives, it must NOT carry the host's raw
                // address (the one unforgivable pass-through).
                val payload = ByteArray(size)
                input.readFully(payload)
                val text = String(payload, Charsets.US_ASCII)
                fail(
                    "the client received a realm list through a corrupt-frame " +
                        "join (opcode 0x${opcode.toString(16)}): \"$text\"",
                )
            } catch (_: java.io.IOException) {
                // Expected on the loud path: the corrupted frame is refused
                // (nothing reaches the client) and the gateway KILLS the
                // lane — the client's read ends in a timeout (nothing sent)
                // or an EOF (the process exited); either way the breach
                // verdict below verifies the error line + exit code 4.
            }
        }
    }

    // -------------------------------------------------------- fake stack

    /** The fake realmd: answers every CMSG_REALM_LIST with a frame whose
     * trailer is corrupted (0x0011 instead of wotlk's 0x0010) — the same
     * corruption class AuthStreamFilterTest's loud-refusal unit pins. */
    private class CorruptRealmServer(port: Int) : AutoCloseable {
        private val server = ServerSocket()
        @Volatile private var stopped = false

        init {
            server.bind(java.net.InetSocketAddress("127.0.0.1", port), 8)
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
                            val input = DataInputStream(client.getInputStream())
                            val output = client.getOutputStream()
                            // The REAL auth dance (code-red R5-1): the
                            // corrupt frame must reach the filter's FRAMED
                            // phase for the loud exit to be exercised.
                            output.write(challengeResponse()); output.flush()
                            input.readFully(ByteArray(75)) // the challenge request
                            output.write(proofResponse()); output.flush()
                            input.readFully(ByteArray(75)) // the proof request
                            while (true) {
                                val opcode = try {
                                    input.readUnsignedByte()
                                } catch (_: java.io.EOFException) {
                                    break
                                }
                                val size = input.readUnsignedShort()
                                input.readFully(ByteArray(size))
                                if (opcode == RealmListRewrite.CMD_REALM_LIST) {
                                    output.write(corruptFixture())
                                    output.flush()
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            runCatching { client.close() }
                        }
                    }, "corrupt-realmd-flow").apply { isDaemon = true }.start()
                }
            }, "corrupt-realmd-accept").apply { isDaemon = true }.start()
        }

        /** The RAW logon-challenge response (AuthSocket.cpp:489-543). */
        private fun challengeResponse(): ByteArray {
            val out = ArrayList<Byte>()
            out.add(0x00); out.add(0x00)
            repeat(32) { out.add(((it * 31 + 7) and 0xff).toByte()) }
            out.add(0x01); out.add(0x07)
            out.add(32)
            repeat(32) { out.add(((it * 17 + 3) and 0xff).toByte()) }
            repeat(32) { out.add(((it * 5 + 11) and 0xff).toByte()) }
            repeat(16) { out.add(((it * 13 + 1) and 0xff).toByte()) }
            out.add(0x00)
            return out.toByteArray()
        }

        /** The RAW logon-proof response (AuthSocket.cpp:135-143, 2.x/3.x). */
        private fun proofResponse(): ByteArray {
            val out = ArrayList<Byte>()
            out.add(0x01); out.add(0x00)
            repeat(20) { out.add(((it * 7 + 5) and 0xff).toByte()) }
            repeat(8) { out.add(0) }
            out.add(0); out.add(0)
            return out.toByteArray()
        }

        /** A valid-shaped wotlk frame with the trailer flipped to 0x0011 —
         * parseable length, unparsable layout (the loud-refusal class). */
        private fun corruptFixture(): ByteArray {
            val name = "Pocket Realm".toByteArray(Charsets.US_ASCII) + 0
            val address = "198.51.100.23:8085".toByteArray(Charsets.US_ASCII) + 0
            val payload = byteArrayOf(
                0, 0, 0, 0, // u32 LE header word
                1, 0, // u16 LE count
                0, 0, // u8 icon, u8 lock
                0x24, // flags: SPECIFYBUILD
            ) + name + address + byteArrayOf(
                0x00, 0x00, 0x00, 0x3f, // f32 LE population 0.5
                3, 1, 0x2c, // chars, category, unk
                3, 3, 5, // build block
                0x34, 0x30, // u16 LE build
                0x11, 0x11, // u16 LE trailer — CORRUPTED (expected 0x0010)
            )
            return byteArrayOf(
                RealmListRewrite.CMD_REALM_LIST.toByte(),
                (payload.size and 0xff).toByte(),
                ((payload.size shr 8) and 0xff).toByte(),
            ) + payload
        }

        override fun close() {
            stopped = true
            runCatching { server.close() }
        }
    }

    // --------------------------------------------------- process plumbing

    private class GatewayProc(private val process: Process) : AutoCloseable {
        private val stdout = java.util.concurrent.LinkedBlockingQueue<String>()
        private val stderrTail = java.util.concurrent.LinkedBlockingQueue<String>()

        /** The READY line's node id (64 hex) and the endpoint's bound UDP
         * socket re-pointed at loopback (the direct lane's dial input). */
        lateinit var nodeHex: String
            private set
        lateinit var dialSocket: String
            private set

        init {
            Thread({
                process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    stdout.add(line)
                }
            }).apply { isDaemon = true; start() }
            Thread({
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    stderrTail.add(line)
                    if (stderrTail.size > 20) stderrTail.poll()
                }
            }).apply { isDaemon = true; start() }
        }

        fun awaitReadyOrThrow(timeoutMs: Long = 45_000): GatewayProc {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                stdout.poll()?.let { line ->
                    if (line.startsWith(GatewayContract.READY_LINE_PREFIX)) {
                        val json = line.removePrefix(GatewayContract.READY_LINE_PREFIX)
                        nodeHex = Regex("\"node\":\"([0-9a-f]{64})\"").find(json)
                            ?.groupValues?.get(1)
                            ?: fail("READY without a node id: $json")
                        // Only the HOST's READY carries bound sockets (the
                        // direct lane's dial input); a join READY does not.
                        Regex("\"addrs\":\\[([^\\]]*)\\]").find(json)
                            ?.groupValues?.get(1)
                            ?.split(',')?.map { it.trim().trim('"') }
                            ?.firstOrNull { it.isNotEmpty() }
                            ?.let { bound ->
                                dialSocket = "127.0.0.1:${bound.substringAfterLast(':')}"
                            }
                        return this
                    }
                    if (line.startsWith(GatewayContract.ERROR_LINE_PREFIX)) {
                        fail("gateway errored before READY: $line")
                    }
                }
                if (!process.isAlive) {
                    fail(
                        "gateway exited (code ${process.exitValue()}) before READY; " +
                            "stderr: ${stderrTail.joinToString(" | ")}",
                    )
                }
                Thread.sleep(50)
            }
            fail("gateway never became READY in ${timeoutMs}ms")
        }

        /** THE loud-exit pin: within the budget the join process must emit
         * GATEWAY-REALMLIST-REWRITE-FAILED on stdout AND exit with code 4.
         * On breach, the failure message documents the observed state. */
        fun awaitRewriteFailureOrDocumentTheBreach(timeoutMs: Long = 15_000) {
            val deadline = System.currentTimeMillis() + timeoutMs
            var sawErrorLine: String? = null
            while (System.currentTimeMillis() < deadline) {
                stdout.poll()?.let { line ->
                    if (line.startsWith(GatewayContract.ERROR_LINE_PREFIX)) {
                        sawErrorLine = line
                    }
                }
                if (!process.isAlive) break
                Thread.sleep(50)
            }
            val alive = process.isAlive
            val exit = if (alive) "STILL ALIVE" else process.exitValue().toString()
            assertTrue(
                sawErrorLine != null,
                "the join gateway never emitted ${GatewayContract.ERR_REWRITE_FAILED} " +
                    "within ${timeoutMs}ms (process $exit; stdout seen so far: " +
                    "${stdout.joinToString(" | ")}; stderr tail: " +
                    "${stderrTail.joinToString(" | ")})",
            )
            assertTrue(
                sawErrorLine!!.contains(GatewayContract.ERR_REWRITE_FAILED),
                "wrong error code: $sawErrorLine",
            )
            if (!alive) {
                kotlin.test.assertEquals(
                    GatewayContract.ExitCode.REALMLIST_REWRITE_FAILED,
                    process.exitValue(),
                    "the loud rewrite failure must exit with code 4",
                )
            } else {
                fail(
                    "the join gateway emitted the error line but NEVER EXITED — " +
                        "the join half-serves instead of dying loudly",
                )
            }
        }

        fun stop() {
            process.destroy()
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }

        override fun close() = stop()
    }

    private fun launchGateway(vararg args: String): GatewayProc {
        val javaBin = Path(
            System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").lowercase().contains("win")) "java.exe" else "java",
        )
        val command = mutableListOf(
            javaBin.toString(),
            "-Djna.tmpdir=E:/pocket_realm_toolchain/tmp/jna",
            "-jar", locateFatJar(),
        )
        command += args.toList()
        return GatewayProc(ProcessBuilder(command).start())
    }

    /** The current fat jar (mirrors the shared E2E's locator). */
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
        fail("the gateway fat jar was not found — run the fatJar task first")
    }

    private fun freeLoopbackPort(): Int =
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    companion object {
        private val SECRET_HOST = "44".repeat(32)
        private val SECRET_JOIN = "55".repeat(32)
    }
}
