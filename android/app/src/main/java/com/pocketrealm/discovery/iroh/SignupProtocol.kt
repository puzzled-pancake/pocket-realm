package com.pocketrealm.discovery.iroh

import org.json.JSONObject

/**
 * Self sign-up over the iroh tunnel: the joiner derives an SRP6 salt and
 * verifier from its password ON ITS OWN DEVICE and sends only
 * {username, sHex, vHex} on the reserved [GatewayContract.PORT_SIGNUP]
 * lane — the password never crosses the tunnel. The host answers one
 * reply line. Shared by the Android in-app gateways, the desktop app and
 * the gateway process, so the frame, the checks and the refusal codes can
 * never drift.
 *
 * Wire: one UTF-8 JSON object per direction, terminated by '\n', at most
 * [MAX_FRAME_BYTES]; the writer then finishes its send half.
 */
object SignupProtocol {

    const val SCHEMA: Int = 1
    const val MAX_FRAME_BYTES: Int = 1_024

    /** Account names: the realm's own account-token grammar. */
    const val USERNAME_MAX_LENGTH: Int = 16
    private const val SRP_HEX_LENGTH = 64

    /** Per-connection rate limit: at most this many requests per tunnel
     * connection, spaced by at least [MIN_REQUEST_INTERVAL_MS]. */
    const val MAX_REQUESTS_PER_CONNECTION: Int = 3
    const val MIN_REQUEST_INTERVAL_MS: Long = 10_000L

    /** How long a joiner waits for the host's answer. */
    const val REPLY_TIMEOUT_MS: Long = 20_000L

    // Reply codes (stable, greppable; the joiner renders [Reply.detail]).
    const val CREATED: String = "CREATED"
    const val SIGNUP_CLOSED: String = "SIGNUP_CLOSED"
    const val NAME_INVALID: String = "NAME_INVALID"
    const val NAME_TAKEN: String = "NAME_TAKEN"
    const val CAP_REACHED: String = "CAP_REACHED"
    const val RATE_LIMITED: String = "RATE_LIMITED"
    const val REQUEST_INVALID: String = "REQUEST_INVALID"
    const val REALM_NOT_READY: String = "REALM_NOT_READY"
    const val FAILED: String = "FAILED"

    /** The LAN sign-up lane's wrong-password refusal (D7/D3: the proof did
     * not match the game password; the sentence matches the join lane's). */
    const val PASSWORD_WRONG: String = "PASSWORD_WRONG"

    val PASSWORD_WRONG_REPLY: Reply = Reply(false, PASSWORD_WRONG, "That password isn't right.")

    data class Request(val username: String, val sHex: String, val vHex: String) {
        init {
            require(isValidUsername(username)) { "the account name must be 1-16 letters or digits" }
            require(isSrpHex(sHex) && isSrpHex(vHex)) { "the verifier must be 64 lowercase hex" }
        }
    }

    data class Reply(val ok: Boolean, val code: String, val detail: String)

    fun isValidUsername(name: String): Boolean =
        name.length in 1..USERNAME_MAX_LENGTH && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }

    fun isSrpHex(value: String): Boolean =
        value.length == SRP_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    fun encodeRequest(request: Request): String = JSONObject()
        .put("schema", SCHEMA)
        .put("username", request.username)
        .put("sHex", request.sHex)
        .put("vHex", request.vHex)
        .toString() + "\n"

    /** The request, or null for anything malformed (wrong schema, extra
     * or missing keys, bad name or verifier, oversize). */
    fun decodeRequest(line: String): Request? = runCatching {
        if (line.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) return null
        val obj = JSONObject(line.trim())
        if (obj.keys().asSequence().toSet() != setOf("schema", "username", "sHex", "vHex")) return null
        if (obj.opt("schema") != SCHEMA) return null
        Request(obj.getString("username"), obj.getString("sHex"), obj.getString("vHex"))
    }.getOrNull()

    fun encodeReply(reply: Reply): String = JSONObject()
        .put("schema", SCHEMA)
        .put("ok", reply.ok)
        .put("code", reply.code)
        .put("detail", reply.detail.take(MAX_DETAIL_CHARS))
        .toString() + "\n"

    fun decodeReply(line: String): Reply? = runCatching {
        if (line.toByteArray(Charsets.UTF_8).size > MAX_FRAME_BYTES) return null
        val obj = JSONObject(line.trim())
        Reply(obj.getBoolean("ok"), obj.getString("code"), obj.optString("detail", "").take(MAX_DETAIL_CHARS))
    }.getOrNull()

    /** Reads one frame from [chunks] (successive stream reads; an empty
     * chunk is EOF): the bytes up to the first '\n', or null when the
     * frame overruns [MAX_FRAME_BYTES] or EOF comes first with nothing. */
    suspend fun readFrame(nextChunk: suspend () -> ByteArray): String? {
        val buffer = java.io.ByteArrayOutputStream()
        while (true) {
            val chunk = nextChunk()
            if (chunk.isEmpty()) break
            val newline = chunk.indexOf('\n'.code.toByte())
            if (newline >= 0) {
                buffer.write(chunk, 0, newline)
                break
            }
            buffer.write(chunk)
            if (buffer.size() > MAX_FRAME_BYTES) return null
        }
        if (buffer.size() == 0 || buffer.size() > MAX_FRAME_BYTES) return null
        return buffer.toString(Charsets.UTF_8.name())
    }

    /**
     * The host's decision before any account write: sign-up must be open,
     * the cap not reached, the name valid and not already signed up here.
     * Null means "create the account"; the create path itself still
     * refuses names that exist on the realm ([fromProvisionCode]).
     */
    fun gate(open: Boolean, cap: Int, signedUp: Collection<String>, request: Request): Reply? = when {
        !open -> Reply(false, SIGNUP_CLOSED, "This realm is not taking sign-ups right now.")
        cap > 0 && signedUp.size >= cap ->
            Reply(false, CAP_REACHED, "This realm has reached its sign-up limit.")
        signedUp.any { it.equals(request.username, ignoreCase = true) } ->
            Reply(false, NAME_TAKEN, "That account name is taken — pick another.")
        else -> null
    }

    /** Maps the host's create-account result code to the joiner's reply. */
    fun fromProvisionCode(ok: Boolean, code: String, username: String): Reply = when {
        ok -> Reply(true, CREATED, "Account $username created — log in with it.")
        code == "ACCOUNT_EXISTS" -> Reply(false, NAME_TAKEN, "That account name is taken — pick another.")
        code == "ACCOUNT_INVALID" -> Reply(false, NAME_INVALID, "The host refused that account name.")
        code in setOf("WORLD_NOT_READY", "WORLD_NOT_OWNED") ->
            Reply(false, REALM_NOT_READY, "The realm is not ready to take sign-ups yet — try again shortly.")
        else -> Reply(false, FAILED, "The host could not create the account ($code).")
    }

    val RATE_LIMITED_REPLY: Reply =
        Reply(false, RATE_LIMITED, "Too many sign-up attempts — wait a little and join again.")
    val INVALID_REPLY: Reply = Reply(false, REQUEST_INVALID, "The sign-up request was malformed.")

    /** One tunnel connection's sign-up allowance. */
    class ConnectionBudget(private val clockMs: () -> Long = System::currentTimeMillis) {
        private var used = 0
        private var lastMs = Long.MIN_VALUE

        @Synchronized
        fun tryAcquire(): Boolean {
            val now = clockMs()
            if (used >= MAX_REQUESTS_PER_CONNECTION) return false
            if (lastMs != Long.MIN_VALUE && now - lastMs < MIN_REQUEST_INTERVAL_MS) return false
            used += 1
            lastMs = now
            return true
        }
    }

    /** The gateway process's stdio sign-up channel: one correlated JSON
     * frame per line, `<prefix>{"id":n,"frame":"<request or reply>"}`. */
    fun encodeChannelLine(prefix: String, id: Long, frame: String): String =
        prefix + JSONObject().put("id", id).put("frame", frame.trimEnd('\n')).toString()

    fun decodeChannelLine(prefix: String, line: String): Pair<Long, String>? = runCatching {
        if (!line.startsWith(prefix)) return null
        val obj = JSONObject(line.removePrefix(prefix))
        obj.getLong("id") to obj.getString("frame")
    }.getOrNull()

    /** The host gateway's request line with the joiner's identity riding
     * beside the frame (a reader that ignores the extra keys still reads
     * the line with [decodeChannelLine]). */
    fun encodeChannelLine(prefix: String, id: Long, frame: String, peer: JoinPeer?): String {
        val obj = JSONObject().put("id", id).put("frame", frame.trimEnd('\n'))
        if (peer != null) obj.put("device", peer.deviceToken).put("node", peer.nodeHex)
        return prefix + obj.toString()
    }

    /** The joiner identity a request line carries, or null without one. */
    fun decodeChannelPeer(prefix: String, line: String): JoinPeer? = runCatching {
        if (!line.startsWith(prefix)) return null
        val obj = JSONObject(line.removePrefix(prefix))
        val device = obj.optString("device", "")
        val node = obj.optString("node", "")
        if (DeviceIdentity.isToken(device) && DeviceIdentity.isToken(node)) JoinPeer(device, node) else null
    }.getOrNull()

    /** Said when a sign-up arrives on a connection that never said hello
     * to a host that requires it. */
    val HELLO_REQUIRED_REPLY: Reply = Reply(false, REQUEST_INVALID, JoinHello.HELLO_REQUIRED_DETAIL)

    private const val MAX_DETAIL_CHARS = 200
}
