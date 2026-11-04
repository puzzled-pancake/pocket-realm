package com.pocketrealm.discovery.iroh

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Who is joining a public realm, as far as the host can tell.
 *
 * The WoW client sends no hardware id, so the joining Pocket Realm app
 * presents one on the tunnel's hello lane ([JoinHello]) before any game
 * byte flows. The raw platform id never leaves the device: the app keys an
 * HMAC with a device secret and sends HMAC(secret, host node id), so each
 * host sees a stable token for this device that no other host can match.
 * A modified app can send any token it likes — bans built on it stop
 * ordinary players, not a determined one.
 */
data class JoinPeer(
    /** HMAC(device secret, host node id), 64 lowercase hex. */
    val deviceToken: String,
    /** The joiner's iroh node id, 64 lowercase hex (authenticated by the
     * transport: the peer holds that key). */
    val nodeHex: String,
)

object DeviceIdentity {

    const val TOKEN_HEX_LENGTH: Int = 64
    private const val INSTALL_ID_BYTES = 32
    private const val SECRET_DOMAIN = "pocketrealm-device-secret-v1"
    private const val TOKEN_DOMAIN = "pocketrealm-device-token-v1:"
    private const val JOIN_KEY_DOMAIN = "pocketrealm-join-key-v1:"
    private const val HMAC = "HmacSHA256"
    private const val BYTE_MASK = 0xff

    /**
     * The device secret. The platform id (ANDROID_ID on Android, the
     * MachineGuid on Windows) is preferred because it survives an app reinstall;
     * without one the per-install random id stands in. Either way the
     * value is hashed here and only ever used as an HMAC key.
     */
    fun deviceSecret(installId: String, platformId: String?): ByteArray {
        val platform = platformId?.trim().orEmpty()
        val source = if (platform.isNotEmpty() && platform !in KNOWN_BOGUS_PLATFORM_IDS) {
            "platform:$platform"
        } else {
            require(installId.isNotBlank()) { "an install id is required without a platform id" }
            "install:${installId.trim()}"
        }
        return MessageDigest.getInstance("SHA-256").run {
            update(SECRET_DOMAIN.toByteArray(Charsets.UTF_8))
            update(0.toByte())
            digest(source.toByteArray(Charsets.UTF_8))
        }
    }

    /** The token one host sees for this device. */
    fun tokenFor(deviceSecret: ByteArray, hostNodeHex: String): String =
        hmacHex(deviceSecret, TOKEN_DOMAIN + hostNodeHex.lowercase())

    /** This device's iroh secret key for one host: stable per (device,
     * host) so the host can recognise the node again, different for every
     * host so node ids cannot be matched across realms. */
    fun joinSecretHex(deviceSecret: ByteArray, hostNodeHex: String): String =
        hmacHex(deviceSecret, JOIN_KEY_DOMAIN + hostNodeHex.lowercase())

    fun isToken(value: String?): Boolean =
        value != null && value.length == TOKEN_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    /** A short, non-reversible label for host screens ("a1b2c3d4"). */
    fun shortLabel(token: String): String = token.take(SHORT_LABEL_CHARS)

    fun freshInstallId(random: SecureRandom = SecureRandom()): String =
        ByteArray(INSTALL_ID_BYTES).also(random::nextBytes).toHex()

    /** The persisted per-install id: read it, or create and store one. */
    fun loadOrCreateInstallId(file: File): String {
        val stored = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrNull()
        if (stored != null && isToken(stored)) return stored
        val fresh = freshInstallId()
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, ".${file.name}.tmp")
            temp.writeText(fresh, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                file.delete()
                temp.renameTo(file)
            }
        }
        return fresh
    }

    private fun hmacHex(key: ByteArray, message: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(key, HMAC))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

    /** Platform ids some devices report for every unit (an emulator
     * build's well-known ANDROID_ID); treated as "no platform id". */
    private val KNOWN_BOGUS_PLATFORM_IDS = setOf("9774d56d682e549c", "unknown", "0")

    private const val SHORT_LABEL_CHARS = 8
}

/**
 * The join password's proof (never the password itself crosses the wire):
 * `proof = HMAC-SHA256(key = SHA-256("pocketrealm-join-pass-v1:" + password),
 * msg = hostNodeHex + ":" + joinerNodeHex)`. The joiner computes it with
 * the host's node id (from the listing/invite) and its OWN node id; the
 * host recomputes the same value with its password. The transport already
 * authenticates the joiner's node id, so a captured proof only works from
 * that same device.
 */
object JoinPasscode {

    private const val DOMAIN: String = "pocketrealm-join-pass-v1:"
    private const val HMAC = "HmacSHA256"
    private const val PROOF_HEX_LENGTH = 64
    private const val BYTE_MASK = 0xff

    /** The per-password HMAC key (64 lowercase hex). The joiner's gateway
     * process receives THIS, never the password. */
    fun keyHex(password: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((DOMAIN + password).toByteArray(Charsets.UTF_8))
            .toHex()

    /** The proof a joiner presents for one (password, host, joiner) triple. */
    fun proof(keyHex: String, hostNodeHex: String, joinerNodeHex: String): String {
        require(isKeyHex(keyHex)) { "the pass key must be 64 lowercase hex" }
        require(DeviceIdentity.isToken(hostNodeHex) && DeviceIdentity.isToken(joinerNodeHex)) {
            "the node ids must be 64 lowercase hex"
        }
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(hexToBytes(keyHex), HMAC))
        return mac.doFinal("$hostNodeHex:$joinerNodeHex".lowercase().toByteArray(Charsets.UTF_8)).toHex()
    }

    fun isKeyHex(value: String?): Boolean =
        value != null && value.length == PROOF_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun isProofHex(value: String?): Boolean = isKeyHex(value)

    /** Constant-time proof comparison (a mismatched length is a mismatch). */
    fun matches(candidate: String, expected: String): Boolean =
        MessageDigest.isEqual(
            candidate.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * The hello lane ([GatewayContract.PORT_HELLO]): right after the tunnel
 * opens, the joiner sends one JSON line naming its device token and waits
 * for the host's one-line answer. Game streams and sign-up only work on a
 * connection the host welcomed. Replies reuse [SignupProtocol.Reply].
 *
 * Schema 2 adds `pass` — the [JoinPasscode] proof (or empty). Schema 1
 * lines still decode; a locked host answers them PASSWORD_REQUIRED (an
 * older app cannot guess the proof), an open host still welcomes them.
 */
object JoinHello {

    const val SCHEMA: Int = 2
    const val SCHEMA_1: Int = 1
    const val WELCOME: String = "WELCOME"
    const val BANNED: String = "BANNED"
    const val REQUEST_INVALID: String = "REQUEST_INVALID"
    const val NO_ANSWER: String = "NO_ANSWER"

    /** The game needs a password this joiner did not present. */
    const val PASSWORD_REQUIRED: String = "PASSWORD_REQUIRED"

    /** The presented password proof did not match. */
    const val PASSWORD_WRONG: String = "PASSWORD_WRONG"

    /** Too many wrong tries; the device is locked out for the window. */
    const val TRY_LATER: String = "TRY_LATER"

    /** The game is at its player limit. */
    const val FULL: String = "FULL"

    /** How long either side waits for the other's hello line. */
    const val TIMEOUT_MS: Long = 10_000L

    val WELCOME_REPLY: SignupProtocol.Reply = SignupProtocol.Reply(true, WELCOME, "Welcome.")
    val BANNED_REPLY: SignupProtocol.Reply =
        SignupProtocol.Reply(false, BANNED, "The host of this realm has banned this device.")
    val INVALID_REPLY: SignupProtocol.Reply =
        SignupProtocol.Reply(false, REQUEST_INVALID, "The join request was malformed.")
    val NO_ANSWER_REPLY: SignupProtocol.Reply =
        SignupProtocol.Reply(false, NO_ANSWER, "The host did not answer the join request.")
    val PASSWORD_REQUIRED_REPLY: SignupProtocol.Reply =
        SignupProtocol.Reply(false, PASSWORD_REQUIRED, "This game needs a password.")
    val PASSWORD_WRONG_REPLY: SignupProtocol.Reply =
        SignupProtocol.Reply(false, PASSWORD_WRONG, "That password isn't right.")
    val TRY_LATER_REPLY: SignupProtocol.Reply =
        SignupProtocol.Reply(false, TRY_LATER, "Too many tries. Wait 10 minutes, then try again.")
    val FULL_REPLY: SignupProtocol.Reply =
        SignupProtocol.Reply(false, FULL, "This game is full right now.")

    /** Said to a joiner whose app never sent a hello (an older app). */
    const val HELLO_REQUIRED_DETAIL: String =
        "This realm needs a newer Pocket Realm to join. Update the app and try again."

    /** One hello request: the device token, the schema it spoke, and —
     * schema 2 — the join password's proof (empty = none presented). */
    data class Request(
        val schema: Int,
        val deviceToken: String,
        val passwordProof: String = "",
    ) {
        init {
            require(schema == SCHEMA_1 || schema == SCHEMA) { "unknown hello schema" }
            require(DeviceIdentity.isToken(deviceToken)) { "the device token must be 64 lowercase hex" }
            require(JoinPasscode.isProofHex(passwordProof) || passwordProof.isEmpty()) {
                "the password proof must be 64 lowercase hex or empty"
            }
        }

        val speaksSchema2: Boolean get() = schema >= SCHEMA
    }

    fun encodeRequest(deviceToken: String, passwordProof: String = ""): String {
        require(DeviceIdentity.isToken(deviceToken)) { "the device token must be 64 lowercase hex" }
        require(JoinPasscode.isProofHex(passwordProof) || passwordProof.isEmpty()) {
            "the password proof must be 64 lowercase hex or empty"
        }
        return JSONObject()
            .put("schema", SCHEMA)
            .put("device", deviceToken)
            .put("pass", passwordProof)
            .toString() + "\n"
    }

    /** The request, or null for anything malformed. Accepts the schema-1
     * shape (device only) exactly as the schema-2 shape. */
    fun decodeRequest(line: String): Request? = runCatching {
        if (line.toByteArray(Charsets.UTF_8).size > SignupProtocol.MAX_FRAME_BYTES) return null
        val obj = JSONObject(line.trim())
        val keys = obj.keys().asSequence().toSet()
        val schema = obj.opt("schema")
        val token = obj.getString("device").takeIf { DeviceIdentity.isToken(it) } ?: return null
        when (schema) {
            SCHEMA_1 -> if (keys != setOf("schema", "device")) return null
            SCHEMA -> {
                if (keys != setOf("schema", "device", "pass")) return null
                val proof = obj.getString("pass")
                if (proof.isNotEmpty() && !JoinPasscode.isProofHex(proof)) return null
                return Request(SCHEMA, token, proof)
            }
            else -> return null
        }
        Request(SCHEMA_1, token)
    }.getOrNull()

    /** The gateway process's stdio admission channel: one correlated line
     * per peer, `<prefix>{"id":n,"device":"..","node":"..","pass":".."}` —
     * `pass` (the password proof, or empty) rides schema 2; lines without
     * it (an older gateway) decode with an empty proof. */
    fun encodeAdmitLine(prefix: String, id: Long, peer: JoinPeer, passwordProof: String = ""): String =
        prefix + JSONObject().put("id", id).put("device", peer.deviceToken)
            .put("node", peer.nodeHex).put("pass", passwordProof).toString()

    /** One admission question from the gateway process to the app. */
    data class AdmitRequest(val id: Long, val peer: JoinPeer, val passwordProof: String)

    fun decodeAdmitLine(prefix: String, line: String): AdmitRequest? = runCatching {
        if (!line.startsWith(prefix)) return null
        val obj = JSONObject(line.removePrefix(prefix))
        val device = obj.getString("device")
        val node = obj.getString("node")
        if (!DeviceIdentity.isToken(device) || !DeviceIdentity.isToken(node)) return null
        val proof = obj.optString("pass", "")
        if (proof.isNotEmpty() && !JoinPasscode.isProofHex(proof)) return null
        AdmitRequest(obj.getLong("id"), JoinPeer(device, node), proof)
    }.getOrNull()

    /** A peer event the gateway process reports to the app (a login seen
     * on the realm lane, or a connection that ended). */
    data class PeerEvent(val kind: String, val peer: JoinPeer, val account: String?)

    const val EVENT_LOGIN: String = "login"
    const val EVENT_LEAVE: String = "leave"

    fun encodeEventLine(prefix: String, event: PeerEvent): String =
        prefix + JSONObject()
            .put("event", event.kind)
            .put("device", event.peer.deviceToken)
            .put("node", event.peer.nodeHex)
            .put("account", event.account ?: "")
            .toString()

    fun decodeEventLine(prefix: String, line: String): PeerEvent? = runCatching {
        if (!line.startsWith(prefix)) return null
        val obj = JSONObject(line.removePrefix(prefix))
        val kind = obj.getString("event").takeIf { it == EVENT_LOGIN || it == EVENT_LEAVE } ?: return null
        val peer = JoinPeer(obj.getString("device"), obj.getString("node"))
        if (!DeviceIdentity.isToken(peer.deviceToken) || !DeviceIdentity.isToken(peer.nodeHex)) return null
        val account = obj.optString("account", "").takeIf { LogonPeek.isAccountName(it) }
        PeerEvent(kind, peer, account)
    }.getOrNull()

    /** The app → host-gateway command closing every live connection of a
     * banned device or node: `<prefix>{"device":"..","node":".."}` (either
     * may be empty). */
    fun encodeDropLine(prefix: String, deviceToken: String?, nodeHex: String?): String =
        prefix + JSONObject().put("device", deviceToken.orEmpty()).put("node", nodeHex.orEmpty()).toString()

    fun decodeDropLine(prefix: String, line: String): Pair<String?, String?>? = runCatching {
        if (!line.startsWith(prefix)) return null
        val obj = JSONObject(line.removePrefix(prefix))
        val device = obj.optString("device", "").takeIf { DeviceIdentity.isToken(it) }
        val node = obj.optString("node", "").takeIf { DeviceIdentity.isToken(it) }
        if (device == null && node == null) null else device to node
    }.getOrNull()
}

/**
 * Reads the account name out of the first client packet on the realm
 * port (AUTH_LOGON_CHALLENGE or AUTH_RECONNECT_CHALLENGE — the same
 * layout on all three game versions): cmd, error, size(2), game(4),
 * version(3), build(2), platform(4), os(4), country(4), timezone(4),
 * ip(4), name length(1), name. The host only looks; the bytes pass on
 * untouched.
 */
object LogonPeek {

    private const val CMD_LOGON_CHALLENGE = 0x00
    private const val CMD_RECONNECT_CHALLENGE = 0x02
    private const val NAME_LENGTH_OFFSET = 33
    private const val NAME_OFFSET = 34
    private const val MAX_NAME_LENGTH = 32
    private const val BYTE_MASK = 0xff

    /** How many bytes the peek ever buffers. */
    const val MAX_PEEK_BYTES: Int = NAME_OFFSET + MAX_NAME_LENGTH

    sealed interface Result {
        data object NeedMore : Result
        data object NotALogon : Result
        data class Account(val name: String) : Result
    }

    fun parse(bytes: ByteArray, length: Int = bytes.size): Result {
        if (length < 1) return Result.NeedMore
        val cmd = bytes[0].toInt() and BYTE_MASK
        if (cmd != CMD_LOGON_CHALLENGE && cmd != CMD_RECONNECT_CHALLENGE) return Result.NotALogon
        if (length <= NAME_LENGTH_OFFSET) return Result.NeedMore
        val nameLength = bytes[NAME_LENGTH_OFFSET].toInt() and BYTE_MASK
        if (nameLength == 0 || nameLength > MAX_NAME_LENGTH) return Result.NotALogon
        if (length < NAME_OFFSET + nameLength) return Result.NeedMore
        val name = String(bytes, NAME_OFFSET, nameLength, Charsets.US_ASCII).uppercase()
        return if (isAccountName(name)) Result.Account(name) else Result.NotALogon
    }

    fun isAccountName(value: String?): Boolean =
        value != null && value.length in 1..MAX_NAME_LENGTH &&
            value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' }

    /** Feed the realm lane's client bytes in order; [feed] answers the
     * account name once, then null forever (or null forever when the
     * first packet is not a logon). */
    class Accumulator {
        private val buffer = ByteArrayOutputStream()
        private var done = false

        @Synchronized
        fun feed(chunk: ByteArray): String? {
            if (done || chunk.isEmpty()) return null
            val room = MAX_PEEK_BYTES - buffer.size()
            buffer.write(chunk, 0, minOf(room, chunk.size))
            return when (val result = parse(buffer.toByteArray())) {
                is Result.Account -> {
                    done = true
                    result.name
                }
                Result.NotALogon -> {
                    done = true
                    null
                }
                Result.NeedMore -> {
                    if (buffer.size() >= MAX_PEEK_BYTES) done = true
                    null
                }
            }
        }
    }
}
