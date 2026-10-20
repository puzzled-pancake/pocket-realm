package com.pocketrealm.gateway

import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.discovery.iroh.RealmListRewrite
import java.io.DataInputStream
import java.net.Socket

/**
 * the fake client lane, extracted from GatewaySubprocessE2E and
 * parameterized by the joiner-side ports (the one-machine E2E dials the
 * real 3724/8085; the docker-isolated reconnect leg's client main runs
 * INSIDE the joiner container). The exchange is the REAL auth dance plus
 * the CMSG_REALM_LIST frame, and the assertion set is THE ask's teeth:
 * the delivered realm list must be the REWRITTEN one (joiner's own
 * loopback world target, host's advertised address never present), and
 * the world lane must tunnel byte-exactly.
 *
 * Deliberately kotlin.test-FREE: this class rides the fakestack jar into
 * the containers, whose classpath is the gateway fat jar only (plain
 * AssertionError failures behave identically under JUnit).
 */
object GatewayClientLane {

    /** Every read's loud deadline (a half-dead tunnel must fail
     * the exchange, never hang the suite's worker). */
    private const val LANE_SO_TIMEOUT_MS: Int = 15_000

    private fun check(condition: Boolean, message: String) {
        if (!condition) throw AssertionError(message)
    }

    /** Dial the join gateway's realm lane, run the challenge/proof
     * dance, request the realm list, read ONE SMSG frame back (the
     * rewritten one), assert the rewrite, and optionally dump the wire
     * payloads (the N3 evidence-run capture). Returns the delivered
     * frame. */
    fun realmListExchange(
        joinerRealmPort: Int,
        payloadDumpDir: String? = null,
    ): ByteArray {
        Socket("127.0.0.1", joinerRealmPort).use { client ->
            client.tcpNoDelay = true
            client.soTimeout = LANE_SO_TIMEOUT_MS
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
            check(opcode == RealmListRewrite.CMD_REALM_LIST) {
                "the reply must be the realm list, was opcode $opcode"
            }
            val payload = ByteArray(size)
            input.readFully(payload)
            val frame = byteArrayOf(opcode.toByte(), size.toByte(), (size shr 8).toByte()) + payload
            // THE ask's assertion: the rewritten list is EXACTLY what
            // the client would dial — the joiner's own loopback world
            // target, never the host's advertised address.
            val parsed = RealmListRewrite.parse(frame, ClientFlavors.WOTLK)
            check(parsed.entries.size == 1) {
                "expected exactly one realm entry, got ${parsed.entries.size}"
            }
            check(parsed.entries[0].address == RealmListRewrite.JOINER_WORLD_TARGET) {
                "the delivered entry must be the joiner's own loopback world " +
                    "target, was '${parsed.entries[0].address}'"
            }
            val text = String(frame, Charsets.US_ASCII)
            check(text.contains(RealmListRewrite.JOINER_WORLD_TARGET)) {
                "the delivered realm list must carry the loopback world target"
            }
            check(!text.contains("198.51.100.23")) {
                "the host's advertised address must never reach the client"
            }
            // Payload capture for the privacy grep (a commit-time
            // fixture: the pytest greps THESE bytes — the real wire
            // payloads the two-process join produced).
            if (payloadDumpDir != null) {
                dumpCapturedPayload(payloadDumpDir, "realm-list-request.bin", request)
                dumpCapturedPayload(payloadDumpDir, "realm-list-rewritten.bin", frame)
            }
            return frame
        }
    }

    /** Writes one captured wire payload into [dir] (the E2E evidence
     * run); the pytest privacy gate reads the committed set. */
    private fun dumpCapturedPayload(dir: String, name: String, bytes: ByteArray) {
        java.io.File(dir).mkdirs()
        java.io.File(dir, name).writeBytes(bytes)
    }

    /** Dial the join gateway's world lane and round-trip a payload
     * through the fake world's echo (raw bytes, no framing — the mux
     * header is the join gateway's own port-id prefix on the iroh
     * side). */
    fun worldEchoExchange(joinerWorldPort: Int) {
        Socket("127.0.0.1", joinerWorldPort).use { client ->
            client.tcpNoDelay = true
            client.soTimeout = LANE_SO_TIMEOUT_MS
            val payload = ByteArray(1024) { (it % 251).toByte() }
            client.getOutputStream().write(payload)
            client.getOutputStream().flush()
            val echo = ByteArray(payload.size)
            val input = DataInputStream(client.getInputStream())
            input.readFully(echo)
            check(payload.contentEquals(echo)) {
                "the world lane must tunnel byte-exactly"
            }
        }
    }

    /** Both lanes in order (the full proof the reconnect delivered a
     * WORKING join, not just a handshake). */
    fun exchangeBothLanes(joinerRealmPort: Int, joinerWorldPort: Int, payloadDumpDir: String? = null) {
        realmListExchange(joinerRealmPort, payloadDumpDir)
        worldEchoExchange(joinerWorldPort)
    }
}
