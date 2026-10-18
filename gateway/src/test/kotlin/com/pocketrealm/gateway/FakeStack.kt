package com.pocketrealm.gateway

import com.pocketrealm.discovery.iroh.RealmListRewrite
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * W6-N4: the fake loopback realm stack, extracted VERBATIM from
 * GatewaySubprocessE2E (the N3 harness) so every reconnect proof and the
 * docker-isolated leg drive the byte-identical servers:
 *
 *  - [FakeRealmServer]: the realmd lane — the raw logon challenge/proof
 *    dance, then the pinned SMSG_REALM_LIST fixture whose world address
 *    points at THIS stack's fake world port (the joiner gateway's rewrite
 *    is what must replace it).
 *  - [FakeEchoServer]: the mangosd lane — raw byte echo.
 *
 * [FakeStackMain] is the docker container's entrypoint (the fake stack
 * must share the HOST gateway's network namespace: the loopback-pinned
 * --realm-target/--world-target can only name the container's own
 * loopback). It is built into gateway-fakestack.jar by the fakeStackJar
 * task and never ships in the production jar's Main-Class.
 */

/** The fake realmd: reads client frames; answers a CMSG_REALM_LIST
 * (opcode 0x10) with the pinned fixture (the host's raw address —
 * what realmd would send before a rewrite). */
class FakeRealmServer(port: Int, private val worldPort: Int) : AutoCloseable {
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
                        output.write(FakeStackProtocol.challengeResponse()); output.flush()
                        input.readFully(ByteArray(75)) // the client's challenge request
                        output.write(FakeStackProtocol.proofResponse()); output.flush()
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
                                output.write(FakeStackProtocol.fixtureFor(worldPort))
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

    override fun close() {
        stopped = true
        runCatching { server.close() }
    }
}

/** The fake mangosd world: echoes every byte back (raw lane). */
class FakeEchoServer(port: Int) : AutoCloseable {
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

/** The wire shapes, pinned in one place (the fixture is the N3 E2E's
 * wotlk SPECIFYBUILD entry, byte-for-byte). */
object FakeStackProtocol {

    /** The RAW logon-challenge response (AuthSocket.cpp:489-543) —
     * the filter's CHALLENGE phase consumes exactly this. */
    fun challengeResponse(): ByteArray {
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
    fun proofResponse(): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0x01); out.add(0x00) // cmd, err = SUCCESS
        repeat(20) { out.add(((it * 7 + 5) and 0xff).toByte()) } // M2
        repeat(8) { out.add(0) } // accountFlags + surveyId
        out.add(0); out.add(0) // unkFlags
        return out.toByteArray()
    }

    /** The pinned fixture with the fake world's port re-pointed at THIS
     * stack (the host gateway dials the alt ports, so the fixture's
     * C-string must name them for the world-lane hop to be real).
     * Numeric fields LITTLE-ENDIAN per the RealmListRewrite doc. */
    fun fixtureFor(worldPort: Int): ByteArray {
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
}

/** The docker container's fake-stack entrypoint: binds the two loopback
 * servers named by --realm-port/--world-port and parks. Unknown args are
 * a loud immediate exit (fail before any bind). */
object FakeStackMain {
    @JvmStatic
    fun main(args: Array<String>) {
        var realmPort = -1
        var worldPort = -1
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--realm-port" -> realmPort = args.getOrNull(++i)?.toIntOrNull() ?: -1
                "--world-port" -> worldPort = args.getOrNull(++i)?.toIntOrNull() ?: -1
                else -> {
                    System.err.println("fakestack: unknown argument '${args[i]}'")
                    kotlin.system.exitProcess(2)
                }
            }
            i += 1
        }
        if (realmPort <= 0 || worldPort <= 0) {
            System.err.println("fakestack: --realm-port and --world-port are required")
            kotlin.system.exitProcess(2)
        }
        println("FAKESTACK_READY realm=$realmPort world=$worldPort")
        System.out.flush()
        val realm = FakeRealmServer(realmPort, worldPort)
        val world = FakeEchoServer(worldPort)
        // Park until killed (the container's lifecycle owns us).
        Thread.currentThread().join()
        runtimeDoNotReach(realm, world)
    }

    /** Keeps the servers referenced after the (unreachable) park so the
     * close paths stay compiled and inspectable; never called. */
    private fun runtimeDoNotReach(vararg closable: AutoCloseable) {
        closable.forEach { runCatching { it.close() } }
    }
}

/** W6-N4: the docker leg's CLIENT-LANE entrypoint — run inside the
 * JOINER container (`docker exec`), dialing the joiner's own loopback
 * listeners exactly the way the local game client would in production
 * (the joiner binds 127.0.0.1-only; a host-side port mapping can never
 * reach it, by design). Runs the full asserting exchange and prints one
 * OK line — the docker harness asserts on the exec's exit code + line. */
object GatewayClientMain {
    @JvmStatic
    fun main(args: Array<String>) {
        var realmPort = -1
        var worldPort = -1
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--realm-port" -> realmPort = args.getOrNull(++i)?.toIntOrNull() ?: -1
                "--world-port" -> worldPort = args.getOrNull(++i)?.toIntOrNull() ?: -1
                else -> {
                    System.err.println("client: unknown argument '${args[i]}'")
                    kotlin.system.exitProcess(2)
                }
            }
            i += 1
        }
        if (realmPort <= 0 || worldPort <= 0) {
            System.err.println("client: --realm-port and --world-port are required")
            kotlin.system.exitProcess(2)
        }
        GatewayClientLane.exchangeBothLanes(realmPort, worldPort)
        println("CLIENT_EXCHANGE_OK realm=$realmPort world=$worldPort")
        System.out.flush()
    }
}
