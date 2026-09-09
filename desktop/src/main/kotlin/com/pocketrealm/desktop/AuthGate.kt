package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RealmEndpoint
import java.io.InputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.experimental.xor
import kotlin.system.exitProcess

/**
 * Phase-3 protocol-auth gate: boot realmd, then prove AUTHENTICATION at
 * protocol level with a minimal WoW 1.12.1 (build 5875) SRP6 logon
 * client — challenge, proof, and the server's M2 verified — against an
 * SRP verifier row seeded directly into classicrealmd.sqlite (the
 * account-creation math mirrored from cmangos's AccountMgr/SRP6).
 *
 * Three independent proofs land:
 *  1. realmd accepts our M1 (it computes its own M1 from the stored
 *     verifier — agreement means the seeded v/s and the handshake math
 *     are both right);
 *  2. the server's M2 == SHA1(A|M1|K) (mutual proof, our K is right);
 *  3. the account row's sessionkey was persisted server-side and
 *     equals our K hex.
 *
 * Run from desktop/: gradlew authGate
 */
// Bring-up gate: any failure exits honestly with the stop attempted.
@Suppress("TooGenericExceptionCaught", "LongMethod")
fun main() {
    val roots = DesktopStorageRoots()
    roots.ensureDirectories()
    DesktopLog.attachFile(roots.logs)
    DesktopLog.i("AuthGate", "phase-3 protocol-auth gate starting")

    val backend = DesktopRuntimeBackend(roots)
    val spec = RuntimeLaunchSpec(
        mode = RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = RealmEndpoint.LOCAL,
        includeClient = false,
    )
    val owner = ComponentOwner(sessionId = "auth-gate", instanceToken = "auth-gate")

    val preflight = kotlinx.coroutines.runBlocking { backend.preflight(spec) }
    if (!preflight.ok) {
        System.err.println("PREFLIGHT FAILED: ${preflight.detail}")
        exitProcess(EXIT_PREFLIGHT)
    }
    kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec) }

    // Seed the verifier row (fresh salt every run; top byte forced
    // nonzero so the challenge packet's unpadded s field is exactly 32
    // bytes — cmangos appends s.AsByteArray() with no length prefix).
    val account = "AUTHGATE"
    val password = "AuthGate-Password-1"
    val srp = VsrpMath.verifierFor(account, password)
    val realmd = DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, "classicrealmd")
    DesktopSqliteConnection(realmd.absolutePath).use { db ->
        db.exec("DELETE FROM account WHERE username = '$account';")
        db.exec(
            "INSERT INTO account (username, gmlevel, v, s, joindate, lockedIp, last_module, locale, os, platform) " +
                "VALUES ('$account', 0, '${srp.vHex}', '${srp.sHex}', " +
                    "'2026-01-01 00:00:00', '0.0.0.0', '', 'enUS', 'Win', 'x86');",
        )
        check(db.queryLong("SELECT COUNT(*) FROM account WHERE username = '$account';") == 1L) {
            "verifier row did not land"
        }
    }
    println(
        "seeded SRP verifier row for $account " +
            "(v=${srp.vHex.take(HEX_ECHO_CHARS)}…, s=${srp.sHex.take(HEX_ECHO_CHARS)}…)"
    )

    kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.REALM, owner, spec) }
    // Wait for READY (not a connect probe: the probe's instant
    // connect+close is itself a connection realmd must chew through).
    val realmReady = kotlinx.coroutines.runBlocking {
        val deadline = System.currentTimeMillis() + LISTEN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val observed = backend.observe(RuntimeComponent.REALM)
            if (observed.state == ComponentLifecycle.READY) return@runBlocking true
            if (observed.state == ComponentLifecycle.FAILED) return@runBlocking false
            Thread.sleep(READY_POLL_SLEEP_MS)
        }
        false
    }
    if (!realmReady) {
        System.err.println("REALM NEVER REACHED READY")
        exitProcess(EXIT_LISTEN)
    }

    val sessionKeyHex = try {
        VsrpClient.logon(LOOPBACK, ServerRuntimeContract.REALM_PORT.toInt(), account, srp)
    } catch (failure: Throwable) {
        DesktopLog.e("AuthGate", "SRP handshake failed: ${failure.message}")
        kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.REALM, owner) }
        exitProcess(EXIT_HANDSHAKE)
    }

    // Proof 3: the server persisted OUR session key on success.
    val persisted = DesktopSqliteConnection(realmd.absolutePath).use { db ->
        db.queryText("SELECT sessionkey FROM account WHERE username = '$account';")
    }
    if (!persisted.equals(sessionKeyHex, ignoreCase = true)) {
        System.err.println("SESSION KEY MISMATCH: server=$persisted client=$sessionKeyHex")
        exitProcess(EXIT_SESSION)
    }
    println(
        "server persisted our session key (K=${sessionKeyHex.take(HEX_ECHO_CHARS)}…)"
    )

    val stop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.REALM, owner) }
    val dbStop = kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) }
    if (!stop.ok || !dbStop.ok) {
        System.err.println("CLEAN STOP FAILED: ${stop.detail} / ${dbStop.detail}")
        exitProcess(EXIT_STOP)
    }
    DesktopLog.i("AuthGate", "phase-3 protocol-auth gate passed")
    println("PHASE-3 PROTOCOL-AUTH GATE PASSED")
}

private const val EXIT_PREFLIGHT = 2
private const val EXIT_LISTEN = 3
private const val EXIT_HANDSHAKE = 4
private const val EXIT_SESSION = 5
private const val EXIT_STOP = 6
private const val LISTEN_TIMEOUT_MS = 20_000L
private const val LOOPBACK = "127.0.0.1"
private const val HEX_ECHO_CHARS = 16
private const val READY_POLL_SLEEP_MS = 250L

/** The account-creation + handshake math, mirrored from cmangos
 * (AccountMgr::CalculateShaPassHash / SRP6.cpp). The server verifies
 * every output independently — this object only has to AGREE. */
internal object VsrpMath {
    val N = BigInteger(SRP_PRIME_HEX, RADIX_HEX)
    val G = BigInteger.valueOf(SRP_GENERATOR)
    private const val SRP_PRIME_HEX =
        "894B645E89E1535BBDAD5B8B290650530801B18EBFBF5E8FAB3C82872A3E9BB7"
    private const val SRP_GENERATOR = 7L

    data class Verifier(val s: ByteArray, val sHex: String, val v: BigInteger, val vHex: String, val x: BigInteger)

    fun verifierFor(account: String, password: String): Verifier {
        // AccountMgr::normalizeString uppercases both before hashing.
        val accountUpper = account.uppercase()
        val passwordUpper = password.uppercase()
        val random = SecureRandom()
        val s = ByteArray(SALT_BYTES)
        do {
            random.nextBytes(s)
            // Top byte nonzero keeps the number 32 bytes; bottom byte
            // nonzero keeps its little-endian minimal form 32 bytes too
            // (the challenge packet carries the unpadded minimal form).
        } while (s[0] == 0.toByte() || s[SALT_BYTES - 1] == 0.toByte())
        val identity = sha1("$accountUpper:$passwordUpper".toByteArray(Charsets.US_ASCII))
        // BigNumber::SetBinary REVERSES its input (a little-endian
        // interpreter): x = the derivation digest read little-endian,
        // over the salt's LE bytes and the raw identity digest (the
        // verifier path's own std::reverse cancels the LE there).
        val x = BigInteger(1, sha1(s.reversedArray(), identity).reversedArray())
        val v = G.modPow(x, N)
        return Verifier(s, s.toHexLower(), v, v.toString(RADIX_HEX), x)
    }

    /** The interleaved 40-byte session-key hash (SRP6::HashSessionKey). */
    fun sessionKey(srpSecret: BigInteger): BigInteger {
        // S contributes its LITTLE-ENDIAN 32 bytes (AsByteArray(32)
        // defaults to reversed=true).
        val t = srpSecret.toPaddedBe(SECRET_BYTES).reversedArray()
        val k = ByteArray(SESSION_KEY_BYTES)
        val even = ByteArray(SECRET_BYTES / 2)
        val odd = ByteArray(SECRET_BYTES / 2)
        for (i in 0 until SECRET_BYTES / 2) {
            even[i] = t[i * 2]
            odd[i] = t[i * 2 + 1]
        }
        val evenHash = sha1(even)
        val oddHash = sha1(odd)
        for (i in 0 until SHA1_BYTES) {
            k[i * 2] = evenHash[i]
            k[i * 2 + 1] = oddHash[i]
        }
        // SetBinary(vK, 40) reverses — the number is vK little-endian.
        return BigInteger(1, k.reversedArray())
    }

    /** SRP6::CalculateProof: M1 = SHA1(N^g || user || s || A || B || K). */
    fun clientProof(
        accountUpper: String,
        s: BigInteger,
        aPub: BigInteger,
        bPub: BigInteger,
        sessionKey: BigInteger,
    ): ByteArray {
        val nHash = sha1(N.toMinLe())
        val gHash = sha1(G.toMinLe())
        // The XOR digest is hashed AS-IS (t3's SetBinary round trip
        // cancels in UpdateBigNumbers); t4 is SHA1(username) — the raw
        // digest bytes, per CalculateProof.
        val t3 = ByteArray(SHA1_BYTES) { i -> (nHash[i] xor gHash[i]) }
        val t4 = sha1(accountUpper.toByteArray(Charsets.US_ASCII))
        return sha1(
            t3,
            t4,
            s.toMinLe(),
            aPub.toMinLe(),
            bPub.toMinLe(),
            sessionKey.toMinLe(),
        )
    }

    /** SRP6::Finalize: the server's proof M2 = SHA1(A || M1 || K). */
    fun serverProof(aPub: BigInteger, m1Wire: ByteArray, sessionKey: BigInteger): ByteArray =
        sha1(aPub.toMinLe(), m1Wire, sessionKey.toMinLe())

    fun sha1(vararg chunks: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-1").digest(chunks.fold(byteArrayOf()) { acc, b -> acc + b })

    private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }

    private const val SALT_BYTES = 32
    private const val SECRET_BYTES = 32
    private const val RADIX_HEX = 16
    private const val SHA1_BYTES = 20
    private const val SESSION_KEY_BYTES = 40
}

/** Minimal big-endian helpers matching cmangos BigNumber::AsByteArray. */
internal fun BigInteger.toMinBe(): ByteArray {
    val raw = toByteArray()
    return if (raw.size > 1 && raw[0] == 0.toByte()) raw.copyOfRange(1, raw.size) else raw
}

/** cmangos's AsByteArray default: minimal bytes, little-endian. */
internal fun BigInteger.toMinLe(): ByteArray = toMinBe().reversedArray()

internal fun BigInteger.toPaddedBe(size: Int): ByteArray {
    val min = toMinBe()
    require(min.size <= size) { "number does not fit in $size bytes" }
    return ByteArray(size).also { System.arraycopy(min, 0, it, size - min.size, min.size) }
}

/** The 1.12.1 wire client: logon challenge -> SRP proof -> M2 verify.
 * Returns the negotiated session key as lowercase hex on success. */
internal object VsrpClient {
    private const val CMD_AUTH_LOGON_CHALLENGE = 0
    private const val CMD_AUTH_LOGON_PROOF = 1
    private const val AUTH_LOGON_SUCCESS = 0
    private const val BUILD_5875 = 5875
    private const val EPHEMERAL_BYTES = 32
    private const val PROOF_BYTES = 20
    private const val SALT_BYTES = 32
    private const val VERSION_CHALLENGE_BYTES = 16
    private const val READ_TIMEOUT_MS = 10_000
    private const val CLIENT_SECRET_BYTES = 19
    private const val GATE_CONNECT_TIMEOUT_MS = 5_000
    private const val SRP_MULTIPLIER = 3L

    // The 1.12 wire format is fixed-width fields; every literal names its
    // field in the adjacent comment (per-field constants would obscure it).
    @Suppress("LongMethod", "MagicNumber")
    fun logon(
        host: String,
        port: Int,
        accountUpper: String,
        verifier: VsrpMath.Verifier,
    ): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), GATE_CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            val name = accountUpper.toByteArray(Charsets.US_ASCII)
            // sAuthLogonChallengeBody: gamename[4] "WoW ", version
            // 1.12.1, build 5875 (LE), platform/os/locale REVERSED
            // (the server un-reverses), timezone bias, ip, name.
            val body = "WoW".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) +
                byteArrayOf(1, 12, 1) + // version 1.12.1 (literals coerce to Byte)
                byteArrayOf(BUILD_5875.toByte(), (BUILD_5875 shr 8).toByte()) +
                reverseAsciiNul("x86") + reverseAsciiNul("Win") + reverseAscii("enUS") +
                byteArrayOf(0, 0, 0, 0) + byteArrayOf(127, 0, 0, 1) +
                byteArrayOf(name.size.toByte()) + name
            out.write(byteArrayOf(CMD_AUTH_LOGON_CHALLENGE.toByte(), 0) +
                byteArrayOf((body.size and 0xFF).toByte(), (body.size shr 8).toByte()) + body)
            out.flush()

            // Response: cmd, error, result, B[32], g_len, g, N_len(uint8),
            // N[32], s[32], version_challenge[16], security_flags.
            val header = input.readExactly(3)
            check(header[0] == CMD_AUTH_LOGON_CHALLENGE.toByte() && header[1] == 0.toByte()) {
                "challenge response header: ${header.toHex()}"
            }
            check(header[2] == AUTH_LOGON_SUCCESS.toByte()) {
                "challenge rejected with result ${header[2].toInt() and 0xFF}"
            }
            val bBytes = input.readExactly(EPHEMERAL_BYTES)
            val gLen = input.readExactly(1)[0].toInt() and 0xFF
            val gBytes = input.readExactly(gLen)
            val nLen = input.readExactly(1)[0].toInt() and 0xFF
            val nBytes = input.readExactly(nLen)
            val sBytes = input.readExactly(SALT_BYTES)
            input.readExactly(VERSION_CHALLENGE_BYTES)
            val securityFlags = input.readExactly(1)[0].toInt() and 0xFF
            check(securityFlags == 0) { "unexpected security flags $securityFlags" }
            check(nBytes.contentEquals(VsrpMath.N.toPaddedBe(EPHEMERAL_BYTES).reversedArray())) {
                "server N mismatch"
            }
            check(gBytes.contentEquals(VsrpMath.G.toMinBe())) { "server g mismatch" }
            check(sBytes.contentEquals(verifier.s.reversedArray())) { "server salt mismatch" }

            // B and N arrive little-endian (AsByteArray's default);
            // the numbers are their big-endian reversal.
            val n = BigInteger(1, nBytes.reversedArray())
            val bPub = BigInteger(1, bBytes.reversedArray())
            val s = BigInteger(1, sBytes.reversedArray())

            // Client ephemeral + session secret. SRP-6 with k=3: the
            // server's B = 3v + g^b, so the client subtracts 3*v̂ where
            // v̂ = g^x is recomputed from its OWN x (it cannot know v),
            // then raises to the full a + u*x — no exponent reduction.
            val aSecret = BigInteger(VsrpRandom.next(CLIENT_SECRET_BYTES))
            val aPub = VsrpMath.G.modPow(aSecret, n)
            val u = BigInteger(1, VsrpMath.sha1(aPub.toMinLe(), bPub.toMinLe()).reversedArray())
            val vHat = VsrpMath.G.modPow(verifier.x, n)
            val base = bPub.subtract(vHat.multiply(BigInteger.valueOf(SRP_MULTIPLIER))).mod(n)
            val secret = base.modPow(aSecret.add(u.multiply(verifier.x)), n)
            val sessionKey = VsrpMath.sessionKey(secret)
            val m1 = VsrpMath.clientProof(accountUpper, s, aPub, bPub, sessionKey)
            // The server compares against M.AsByteArray(): M's number
            // is the digest read little-endian, whose LE form is the
            // RAW digest — so M1 crosses the wire unreversed. A also
            // crosses little-endian (SetBinary reverses on read).
            val proof = byteArrayOf(CMD_AUTH_LOGON_PROOF.toByte()) +
                aPub.toPaddedBe(EPHEMERAL_BYTES).reversedArray() + m1 +
                ByteArray(PROOF_BYTES) { 0 } + byteArrayOf(0, 0) // crc, keys, flags
            out.write(proof)
            out.flush()

            // 1.12.1 proof response: cmd, error, M2[20], LoginFlags.
            val response = input.readExactly(2 + PROOF_BYTES + 1)
            check(response[0] == CMD_AUTH_LOGON_PROOF.toByte()) { "proof response cmd ${response[0]}" }
            check(response[1] == AUTH_LOGON_SUCCESS.toByte()) {
                "logon proof REJECTED (result=${response[1].toInt() and 0xFF})"
            }
            val m2 = response.copyOfRange(2, 2 + PROOF_BYTES)
            val expected = VsrpMath.serverProof(aPub, m1, sessionKey)
            check(m2.contentEquals(expected)) { "server M2 mismatch" }
            println("logon proof accepted; M2 verified; account=$accountUpper")
            return sessionKey.toString(16)
        }
    }

    /** cmangos reverses the platform/os/locale strings on read; the
     * vanilla client sends them reversed. */
    private fun reverseAscii(value: String): ByteArray =
        value.toByteArray(Charsets.US_ASCII).reversedArray()

    /** platform/os are 3-character fields NUL-terminated to 4 bytes, so
     * the reversed wire form carries the NUL FIRST. */
    private fun reverseAsciiNul(value: String): ByteArray =
        byteArrayOf(0) + value.toByteArray(Charsets.US_ASCII).reversedArray()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(buffer, read, count - read)
            check(n > 0) { "socket closed after $read/$count bytes" }
            read += n
        }
        return buffer
    }
}

/** Deterministic-length random byte arrays for the client secret. */
internal object VsrpRandom {
    private val random = java.security.SecureRandom()
    fun next(bytes: Int): ByteArray = ByteArray(bytes).also { random.nextBytes(it) }
}
