package com.pocketrealm.desktop

import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * The 1.12.1 world-protocol client for the RP harness: a REAL player
 * session (real TCP socket, real SRP6 session key) so every conversational
 * gate sees isRealPlayer()=true — the thing the world-chat injection can
 * never provide on its own.
 *
 * Wire facts (ported from native/cmangos/src/game/Server/WorldSocket.cpp
 * + AuthCrypt.cpp, the pinned 5875 lane):
 *  - server opens with SMSG_AUTH_CHALLENGE (raw 4-byte header + u32 seed);
 *  - CMSG_AUTH_SESSION: build u32, unk u32, account zstr, client-seed u32,
 *    SHA1(account ‖ le32(0) ‖ le32(clientSeed) ‖ le32(serverSeed) ‖ K) —
 *    K is the sessionkey hex column, consumed as minimal little-endian
 *    bytes (BigNumber::AsByteArray's form);
 *  - after auth both directions crypt their headers only: client header
 *    6 bytes (u16 size BE incl. the 4 cmd bytes + u32 cmd LE), server
 *    header 4 bytes (u16 size BE incl. the 2 cmd bytes + u16 cmd LE);
 *  - chat: CMSG_MESSAGECHAT = type u32, lang u32, [to zstr,] msg zstr.
 */
@Suppress("MagicNumber", "TooManyFunctions") // fixed-width 1.12 wire fields
internal object RpWowClient {
    private const val CMSG_CHAR_CREATE = 0x036
    private const val CMSG_CHAR_ENUM = 0x037
    private const val CMSG_PLAYER_LOGIN = 0x03D
    private const val CMSG_MESSAGECHAT = 0x095
    private const val SMSG_MESSAGECHAT = 0x096
    private const val SMSG_TEXT_EMOTE = 0x105
    private const val SMSG_NEW_WORLD = 0x03E
    private const val CMSG_PING = 0x1DC
    private const val MSG_MOVE_WORLDPORT_ACK = 0x0DC
    private const val SMSG_AUTH_CHALLENGE = 0x1EC
    private const val CMSG_AUTH_SESSION = 0x1ED
    private const val SMSG_AUTH_RESPONSE = 0x1EE
    private const val SMSG_CHAR_ENUM = 0x03B
    private const val SMSG_CHAR_CREATE = 0x03A

    private const val BUILD_5875 = 5875
    private const val CHAT_TYPE_SAY = 0
    private const val CHAT_TYPE_PARTY = 1
    private const val CHAT_TYPE_YELL = 5
    private const val CHAT_TYPE_WHISPER = 6
    private const val CHAT_MSG_MONSTER_SAY = 0x0B
    private const val CHAT_MSG_MONSTER_YELL = 0x0C
    private const val CHAT_MSG_MONSTER_EMOTE = 0x0D
    private const val CHAT_MSG_CHANNEL = 0x0E
    private const val CHAT_MSG_MONSTER_WHISPER = 0x1A
    private const val CHAT_MSG_RAID_BOSS_WHISPER = 0x59
    private const val CHAT_MSG_RAID_BOSS_EMOTE = 0x5A
    private const val LANG_UNIVERSAL = 0

    /** One parsed SMSG_MESSAGECHAT row (the harness's structured chat
     * capture): the 1.12 wire layout carries the sender as a guid, not a
     * name - the harness resolves guids against the bot list itself. */
    internal data class ChatLine(val type: Int, val senderGuid: Long, val text: String)

    /** The auth + header crypt, one instance per world connection. */
    private class Crypt(keyBytes: ByteArray) {
        private val key = keyBytes
        private var sendI = 0
        private var sendJ = 0
        private var recvI = 0
        private var recvJ = 0

        fun encrypt(data: ByteArray) {
            for (t in data.indices) {
                sendI %= key.size
                val x = ((data[t].toInt() and 0xFF) xor (key[sendI].toInt() and 0xFF)) + sendJ
                ++sendI
                sendJ = x and 0xFF
                data[t] = sendJ.toByte()
            }
        }

        fun decrypt(data: ByteArray) {
            for (t in data.indices) {
                recvI %= key.size
                val c = data[t].toInt() and 0xFF
                val x = ((c - recvJ) and 0xFF) xor (key[recvI].toInt() and 0xFF)
                ++recvI
                recvJ = c
                data[t] = x.toByte()
            }
        }
    }

    /** One authenticated-or-not world socket with a drain thread. */
    class World(
        host: String,
        worldPort: Int,
        private val account: String,
        sessionKeyHex: String,
    ) {
        private val socket = Socket()
        private val input: InputStream
        private val output: OutputStream
        private val keyBytes: ByteArray =
            BigInteger(sessionKeyHex, 16).toMinLe()
        private var crypt: Crypt? = null
        private val inbound = ConcurrentLinkedQueue<Pair<Int, ByteArray>>()
        @Volatile private var reading = true
        // the logged-in player's own guid: the capture drops self-sent rows
        @Volatile private var selfGuid = 0L
        // the reader stays parked until the auth handshake has armed the
        // header crypt (see auth())
        private val authLatch = java.util.concurrent.CountDownLatch(1)

        init {
            socket.connect(InetSocketAddress(host, worldPort), 10_000)
            socket.soTimeout = 500
            socket.tcpNoDelay = true
            input = socket.getInputStream()
            output = socket.getOutputStream()
            thread(name = "rp-wow-reader", isDaemon = true) { readLoop() }
        }

        fun close() {
            reading = false
            runCatching { socket.close() }
        }

        /** AUTH_CHALLENGE -> AUTH_SESSION -> AUTH_RESPONSE. Returns the
         * response byte (0x0C is AUTH_OK on the 5875 lane). */
        fun auth(): Int {
            val header = readRawExactly(4)
            val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
            val cmd = (header[2].toInt() and 0xFF) or ((header[3].toInt() and 0xFF) shl 8)
            check(cmd == SMSG_AUTH_CHALLENGE) { "expected AUTH_CHALLENGE, got 0x${cmd.toString(16)}" }
            val seedBytes = readRawExactly(size - 2)
            val serverSeed = (seedBytes[0].toInt() and 0xFF) or
                ((seedBytes[1].toInt() and 0xFF) shl 8) or
                ((seedBytes[2].toInt() and 0xFF) shl 16) or
                ((seedBytes[3].toInt() and 0xFF) shl 24)
            val clientSeed = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()

            val sha = java.security.MessageDigest.getInstance("SHA-1")
            sha.update(account.toByteArray(Charsets.US_ASCII))
            sha.update(byteArrayOf(0, 0, 0, 0)) // t = 0
            sha.update(le32(clientSeed))
            sha.update(le32(serverSeed))
            sha.update(keyBytes)
            val digest = sha.digest()

            val name = account.toByteArray(Charsets.US_ASCII)
            // the CMaNGOS anticheat reads an addon block after the digest:
            // u32 UNCOMPRESSED size + a zlib stream of addon records
            // (name zstr, flags u8, moduluscrc u32, urlcrc u32). The
            // reserve buffer is sized from that u32 and parsed whole, so
            // the stream must decompress to EXACTLY that length with one
            // clean record: "RPTest", flags 0, the standard Blizzard
            // modulus CRC 0x4C1C776D, urlcrc 0.
            val addonBlock = byteArrayOf(
                16, 0, 0, 0,             // u32 uncompressed size
                0x78, 0x9C.toByte(), 0x0B, 0x0A, 0x08, 0x49, 0x2D, 0x2E,
                0x61, 0x60, 0xC8.toByte(), 0x2D, 0x97.toByte(), 0xF1.toByte(),
                0x61, 0x00, 0x02, 0x00, 0x26, 0xC6.toByte(), 0x03, 0x8F.toByte(),
            )
            val body = ByteArray(8 + name.size + 1 + 4 + 20 + addonBlock.size)
            var o = 0
            o = writeLe32(body, o, BUILD_5875)
            o = writeLe32(body, o, 0)
            System.arraycopy(name, 0, body, o, name.size); o += name.size
            body[o++] = 0
            o = writeLe32(body, o, clientSeed)
            System.arraycopy(digest, 0, body, o, 20); o += 20
            System.arraycopy(addonBlock, 0, body, o, addonBlock.size); o += addonBlock.size
            sendPacket(CMSG_AUTH_SESSION, body, encrypted = false)
            // the server switches its header crypt the moment it has our
            // AUTH_SESSION - from here every header is encrypted. The
            // reader stays parked on authLatch until this line, so it can
            // never mangle the first encrypted header.
            crypt = Crypt(keyBytes)
            authLatch.countDown()
            // the server kicks quiet sessions; the real client pings ~25s.
            // Started here (post-AUTH_SESSION) so no ping can outrun the
            // server's own crypt arming during the handshake.
            thread(name = "rp-wow-pinger", isDaemon = true) {
                var nonce = 0
                while (reading) {
                    Thread.sleep(25_000)
                    if (!reading) break
                    runCatching {
                        val body = ByteArray(8)
                        writeLe32(body, 0, nonce++)
                        writeLe32(body, 4, 50)
                        sendPacket(CMSG_PING, body)
                    }
                }
            }

            val response = waitFor(SMSG_AUTH_RESPONSE, 600_000)
                ?: error("no AUTH_RESPONSE (reader saw ${inbound.size} packets)")
            return response.second[0].toInt() and 0xFF
        }

        /** CMSG_CHAR_ENUM -> [(guid, name)]; parses the first record of
         * each enum row (guid + name) — a harness account has few chars. */
        fun charEnum(): List<Pair<Long, String>> {
            inbound.clear()
            sendPacket(CMSG_CHAR_ENUM, ByteArray(0))
            val packet = waitFor(SMSG_CHAR_ENUM, 300_000)
                ?: error("no CHAR_ENUM")
            val payload = packet.second
            var o = 0
            val count = payload[o].toInt() and 0xFF; o += 1
            val chars = mutableListOf<Pair<Long, String>>()
            for (i in 0 until count) {
                var guid = 0L
                for (b in 0 until 8) guid = guid or ((payload[o + b].toLong() and 0xFF) shl (8 * b))
                o += 8
                var nameEnd = o
                while (nameEnd < payload.size && payload[nameEnd] != 0.toByte()) nameEnd++
                if (nameEnd >= payload.size) break
                val name = String(payload, o, nameEnd - o, Charsets.US_ASCII)
                o = nameEnd + 1
                chars.add(guid to name)
                // the remaining per-record fields are skipped wholesale:
                // the harness only needs guid + name, and its accounts
                // hold at most a couple of characters
                o += 60
            }
            return chars
        }

        /** CMSG_CHAR_CREATE; returns the SMSG_CHAR_CREATE result byte
         * (0x00 = success). Human warrior, default looks. */
        fun charCreate(name: String): Int {
            val body = mutableListOf<Byte>()
            body.addAll(name.toByteArray(Charsets.US_ASCII).toList())
            body.add(0)
            body.addAll(
                listOf<Byte>(1, 1, 0, 0, 0, 0, 0, 0, 0, 0), // human warrior male, plain
            )
            sendPacket(CMSG_CHAR_CREATE, body.toByteArray())
            val packet = waitFor(SMSG_CHAR_CREATE, 300_000)
                ?: error("no CHAR_CREATE result")
            return packet.second[0].toInt() and 0xFF
        }

        fun playerLogin(guid: Long) {
            selfGuid = guid
            val body = ByteArray(8)
            for (b in 0 until 8) body[b] = ((guid shr (8 * b)) and 0xFF).toByte()
            sendPacket(CMSG_PLAYER_LOGIN, body)
        }

        fun say(text: String) = chat(CHAT_TYPE_SAY, "", text)
        fun whisper(to: String, text: String) = chat(CHAT_TYPE_WHISPER, to, text)

        /** Send chat, then collect chat traffic that arrives within
         * [drainMs], parsed per the 1.12 SMSG_MESSAGECHAT layout
         * (ChatHandler::BuildChatPacket): type, sender guid, text - so
         * self-echo, inform rows and bot replies are distinguishable
         * instead of printable-run guesses. Text emotes observed in the
         * same window land in [emotes] (SMSG_TEXT_EMOTE). */
        fun chatAndCapture(
            kind: String,
            to: String,
            text: String,
            drainMs: Long,
            emotes: MutableList<Map<String, Long>> = mutableListOf(),
            includeSelf: Boolean = false,
        ): List<ChatLine> {
            inbound.clear()
            when (kind) {
                "say" -> say(text)
                "whisper" -> whisper(to, text)
                else -> error("unknown chat kind $kind")
            }
            if (kind == "say" && text.startsWith(".")) {
                // GM commands run on the world thread's CLI queue; give the
                // command room to land before the next op. A far teleport
                // completes via SMSG_NEW_WORLD -> worldport-ack in the
                // reader loop (the old unconditional ack here fired while
                // the player was still in world and completed nothing).
            }
            // the sender's own line echoes back as WHISPER_INFORM (type 7);
            // capture only the reply message types (6 whisper, 0 say) and
            // drop any residual self-sent rows by guid
            val replyType = if (kind == "whisper") CHAT_TYPE_WHISPER else CHAT_TYPE_SAY
            val deadline = System.currentTimeMillis() + drainMs
            val lines = mutableListOf<ChatLine>()
            while (System.currentTimeMillis() < deadline) {
                val hit = waitForAny(deadline, SMSG_MESSAGECHAT, SMSG_TEXT_EMOTE)
                if (hit == null) break
                val (opcode, payload) = hit
                if (opcode == SMSG_TEXT_EMOTE) {
                    parseTextEmote(payload)?.let { emotes.add(it) }
                    continue
                }
                val line = parseChatLine(payload) ?: continue
                if (line.type != replyType) continue
                // self-sent rows are replies-noise for conversation turns,
                // but with includeSelf they are the LIVENESS proof: the
                // server received and routed my own say back to me
                if (line.senderGuid == selfGuid && !includeSelf) continue
                lines.add(line)
                // an actual spoken reply (>6 words) ends the listen
                // window early - the turn is done
                if (line.text.split(" ").size >= 6 &&
                    System.currentTimeMillis() + 4000 < deadline) {
                    return lines
                }
            }
            return lines
        }

        // ---- internals --------------------------------------------------

        /** Parse one SMSG_MESSAGECHAT payload per the 1.12 wire layout
         * (ChatHandler::BuildChatPacket): u8 type, u32 lang, a type-shaped
         * guid/name block, u32 len + message zstr, u8 chatTag. */
        private fun parseChatLine(p: ByteArray): ChatLine? {
            if (p.size < 7) return null
            var off = 0
            val type = p[off].toInt() and 0xFF; off += 1
            off += 4 // language
            val senderGuid: Long
            when (type) {
                CHAT_TYPE_SAY, CHAT_TYPE_PARTY, CHAT_TYPE_YELL -> {
                    // senderGuid written twice (sender + receiver echo slot)
                    senderGuid = readLe64(p, off); off += 16
                }
                CHAT_MSG_MONSTER_WHISPER, CHAT_MSG_RAID_BOSS_WHISPER,
                CHAT_MSG_RAID_BOSS_EMOTE, CHAT_MSG_MONSTER_EMOTE -> {
                    // name (len + zstr) then target guid; sender is nameless
                    off += 4
                    off += 8
                    senderGuid = 0L
                }
                CHAT_MSG_MONSTER_SAY, CHAT_MSG_MONSTER_YELL -> {
                    senderGuid = readLe64(p, off); off += 8
                    off += 4 // name length
                    off += 8 // target guid
                }
                CHAT_MSG_CHANNEL -> {
                    // channel name zstr, u32 playerRank, sender guid
                    while (off < p.size && p[off] != 0.toByte()) off += 1
                    off += 1
                    off += 4
                    senderGuid = readLe64(p, off); off += 8
                }
                else -> {
                    // whisper, whisper-inform, emote, system: guid once
                    senderGuid = readLe64(p, off); off += 8
                }
            }
            if (off + 4 > p.size) return null
            val len = readLe32(p, off).toInt(); off += 4
            if (len <= 0 || off + len > p.size) return null
            return ChatLine(type, senderGuid, String(p, off, len - 1, Charsets.US_ASCII))
        }

        /** Parse one SMSG_TEXT_EMOTE payload: u64 guid, u32 textEmote,
         * u32 emoteNum, u32 namlen, [name zstr]. */
        private fun parseTextEmote(p: ByteArray): Map<String, Long>? {
            if (p.size < 20) return null
            return mapOf(
                "guid" to readLe64(p, 0),
                "textEmote" to readLe32(p, 8),
                "emoteNum" to readLe32(p, 12),
            )
        }


        private fun chat(type: Int, to: String, text: String) {
            val toBytes = to.toByteArray(Charsets.US_ASCII)
            val textBytes = text.toByteArray(Charsets.US_ASCII)
            var body = ByteArray(8)
            writeLe32(body, 0, type)
            writeLe32(body, 4, LANG_UNIVERSAL)
            if (type == CHAT_TYPE_WHISPER) {
                body += toBytes + byteArrayOf(0)
            }
            body += textBytes + byteArrayOf(0)
            sendPacket(CMSG_MESSAGECHAT, body)
        }

        private fun sendPacket(cmd: Int, body: ByteArray, encrypted: Boolean = true) {
            val size = body.size + 4
            val header = byteArrayOf(
                ((size shr 8) and 0xFF).toByte(), (size and 0xFF).toByte(),
                (cmd and 0xFF).toByte(), ((cmd shr 8) and 0xFF).toByte(),
                ((cmd shr 16) and 0xFF).toByte(), ((cmd shr 24) and 0xFF).toByte(),
            )
            synchronized(output) {
                if (encrypted) {
                    val crypted = crypt ?: error("not authenticated")
                    crypted.encrypt(header)
                }
                output.write(header)
                output.write(body)
                output.flush()
            }
        }

        private fun readLoop() {
            try {
                authLatch.await()
                while (reading) {
                    val header = try {
                        readRawExactly(4)
                    } catch (_: java.io.InterruptedIOException) {
                        continue
                    } catch (_: java.net.SocketException) {
                        return
                    }
                    if (crypt != null) crypt!!.decrypt(header)
                    val size = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
                    val cmd = (header[2].toInt() and 0xFF) or ((header[3].toInt() and 0xFF) shl 8)
                    System.err.println("reader hdr: %02X %02X %02X %02X -> size=%d cmd=0x%04X"
                        .format(header[0], header[1], header[2], header[3], size, cmd))
                    if (size < 2) return
                    val payload = try {
                        readRawExactly(size - 2)
                    } catch (_: java.io.InterruptedIOException) {
                        continue
                    } catch (_: java.net.SocketException) {
                        return
                    }
                    if (cmd == SMSG_NEW_WORLD) {
                        // far-teleport completion (Player::SetSemaphoreTeleportFar):
                        // the server holds the player out of the world until the
                        // client acks the new-world prompt - without this the
                        // player sits in transfer limbo and every later packet
                        // lands on a mapless player
                        sendPacket(MSG_MOVE_WORLDPORT_ACK, ByteArray(0))
                    }
                    System.err.println("reader: cmd=0x%04X size=%d payload0=%02X"
                        .format(cmd, size, if (payload.isEmpty()) 0 else payload[0]))
                    inbound.add(cmd to payload)
                }
            } catch (_: Throwable) {
                // the socket died; the op layer reports the stalled wait
            }
        }

        private fun waitFor(opcode: Int, ms: Long, absoluteDeadline: Boolean = false): Pair<Int, ByteArray>? {
            val deadline = if (absoluteDeadline) ms else System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < deadline) {
                val hit = inbound.firstOrNull { it.first == opcode }
                if (hit != null) {
                    inbound.remove(hit)
                    return hit
                }
                Thread.sleep(20)
            }
            return null
        }

        /** First queued packet matching ANY of [opcodes], polled until the
         * absolute wall-clock [deadline] - so one drain loop can consume
         * interleaved chat and text-emote traffic. */
        private fun waitForAny(deadline: Long, vararg opcodes: Int): Pair<Int, ByteArray>? {
            while (System.currentTimeMillis() < deadline) {
                val hit = inbound.firstOrNull { it.first in opcodes }
                if (hit != null) {
                    inbound.remove(hit)
                    return hit
                }
                Thread.sleep(20)
            }
            return null
        }

        private fun readRawExactly(n: Int): ByteArray {
            val data = ByteArray(n)
            var off = 0
            while (off < n) {
                try {
                    val read = input.read(data, off, n - off)
                    if (read < 0) error("socket closed mid-read")
                    off += read
                } catch (_: java.io.InterruptedIOException) {
                    // soTimeout tick with a partial frame - keep the bytes
                    // already read and keep waiting for the rest
                }
            }
            return data
        }

        private fun readLe32(p: ByteArray, off: Int): Long =
            ((p[off].toLong() and 0xFF)) or
            ((p[off + 1].toLong() and 0xFF) shl 8) or
            ((p[off + 2].toLong() and 0xFF) shl 16) or
            ((p[off + 3].toLong() and 0xFF) shl 24)

        private fun readLe64(p: ByteArray, off: Int): Long =
            (readLe32(p, off) and 0xFFFFFFFFL) or
            ((readLe32(p, off + 4) and 0xFFFFFFFFL) shl 32)

        private fun le32(value: Int): ByteArray = byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )

        private fun writeLe32(target: ByteArray, offset: Int, value: Int): Int {
            target[offset] = (value and 0xFF).toByte()
            target[offset + 1] = ((value shr 8) and 0xFF).toByte()
            target[offset + 2] = ((value shr 16) and 0xFF).toByte()
            target[offset + 3] = ((value shr 24) and 0xFF).toByte()
            return offset + 4
        }
    }
}
