package com.pocketrealm.server

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The remote-account provisioning wire (design-remote-accounts):
 * length-prefixed UTF-8 JSON, one request per connection, a hard 4 KiB
 * request cap (matching RuntimeLaunchSpec.fromJson's 4096 budget) and a
 * short deadline. Android-free pure JVM so the FRIEND side runs on both
 * platforms; the desktop host side (the provisioning listener) consumes
 * the same framing helpers so the two ends cannot drift.
 *
 * Secrecy shape: the request carries `{inviteId, token, username,
 * sHex, vHex}` — the password is typed on the friend's device and only
 * ever leaves it as the derived SRP6 verifier. v1 confidentiality is the
 * LAN/tunnel itself; v1 authentication is the single-use invite token.
 *
 * Refusal oracle rule: the response for an unknown invite, an expired
 * or burned token, and a rate-locked source is byte-identical —
 * [REFUSED_RESPONSE]. Only a SUBMITTED (or later) verdict tells the friend
 * anything.
 */
object FriendProvisionProtocol {
    const val SCHEMA = 1

    /** Hard cap on one framed request, in bytes. */
    const val MAX_REQUEST_BYTES = 4_096

    /** Hard cap on one framed response, in bytes (a PROVISIONED verdict is tiny). */
    const val MAX_RESPONSE_BYTES = 1_024

    /**
     * The one refusal blob. Byte-identical for unknown token, expired,
     * burned, malformed, and rate-locked — no oracle.
     */
    const val REFUSED_RESPONSE = "{\"schema\":$SCHEMA,\"ok\":false,\"error\":\"REQUEST_REFUSED\"}"

    /** The accepted verdict's state values (the invite row's post-submit states). */
    const val STATE_SUBMITTED = "SUBMITTED"

    fun encodeRequest(inviteId: String, token: String, username: String, sHex: String, vHex: String): ByteArray =
        frame(
            JSONObject()
                .put("schema", SCHEMA)
                .put("inviteId", inviteId)
                .put("token", token)
                .put("username", username)
                .put("sHex", sHex)
                .put("vHex", vHex)
                .toString(),
        )

    /** Decodes one framed request; null = malformed at any leg (the caller answers [REFUSED_RESPONSE]). */
    fun decodeRequest(payload: ByteArray): JSONObject? {
        if (payload.size > MAX_REQUEST_BYTES) return null
        val value = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return null
        val allowed = setOf("schema", "inviteId", "token", "username", "sHex", "vHex")
        val actual = buildSet { value.keys().forEachRemaining(::add) }
        if (actual != allowed) return null
        if (value.optInt("schema", -1) != SCHEMA) return null
        val token = value.optString("token")
        val username = value.optString("username")
        val sHex = value.optString("sHex")
        val vHex = value.optString("vHex")
        if (token.isEmpty() || username.isEmpty()) return null
        // The identity and verifier rules the host enforces anyway
        // (requireAccountToken / 64 lowercase hex chars) — a malformed
        // request is simply refused, never half-interpreted.
        val usernameValid = runCatching {
            ServerRuntimeContract.requireAccountToken("username", username)
        }.isSuccess
        if (!usernameValid) return null
        if (!isSrpHex(sHex) || !isSrpHex(vHex)) return null
        return value
    }

    fun encodeSubmitted(inviteId: String): String = JSONObject()
        .put("schema", SCHEMA)
        .put("ok", true)
        .put("state", STATE_SUBMITTED)
        .put("inviteId", inviteId)
        .toString()

    fun encodeProvisioned(inviteId: String, username: String, accountId: Long): String = JSONObject()
        .put("schema", SCHEMA)
        .put("ok", true)
        .put("state", "PROVISIONED")
        .put("inviteId", inviteId)
        .put("username", username)
        .put("accountId", accountId)
        .toString()

    /** 64 lowercase hex chars — a 32-byte big number's exact wire form. */
    fun isSrpHex(value: String): Boolean =
        value.length == 64 && value.all { (it in '0'..'9') || (it in 'a'..'f') }

    /** The D7 LAN sign-up request kind: account creation on the
     * friend-access port without an invite, gated on the host's switches
     * and (when the game is locked) the game password's proof. */
    const val KIND_SIGNUP = "signup"

    /**
     * The D3 proof's pinned node legs for the LAN sign-up lane: no iroh
     * descriptor is exchanged on this lane, so BOTH legs carry the lane
     * name — the proof binds the derived key (and so the password) to
     * this lane's shape, exactly the D3 formula with a fixed message.
     */
    const val LAN_SIGNUP_NODE = "lan"

    /** One D7 LAN sign-up request (the friend's account ask). */
    data class SignupRequest(val username: String, val sHex: String, val vHex: String, val pass: String)

    /**
     * The friend-side proof for a game password on the LAN sign-up lane:
     * the D3 formula verbatim (the same per-password key and HMAC shape)
     * with BOTH node legs pinned to the lane literal — [JoinPasscode.proof]
     * validates node ids as 64-hex, which the lane name deliberately is
     * not, so the math is mirrored here.
     */
    fun lanSignupPass(gamePassword: String): String {
        val key = com.pocketrealm.discovery.iroh.JoinPasscode.keyHex(gamePassword)
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(
            javax.crypto.spec.SecretKeySpec(
                key.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                "HmacSHA256",
            ),
        )
        return mac.doFinal("$LAN_SIGNUP_NODE:$LAN_SIGNUP_NODE".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun encodeSignupRequest(username: String, sHex: String, vHex: String, pass: String): ByteArray =
        frame(
            JSONObject()
                .put("schema", SCHEMA)
                .put("kind", KIND_SIGNUP)
                .put("username", username)
                .put("sHex", sHex)
                .put("vHex", vHex)
                .put("pass", pass)
                .toString(),
        )

    /** Decodes one LAN sign-up request; null = malformed (the caller
     * answers the SIGNUP_CLOSED-shaped refusal, never half-interprets). */
    fun decodeSignupRequest(payload: ByteArray): SignupRequest? {
        if (payload.size > MAX_REQUEST_BYTES) return null
        val value = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return null
        val allowed = setOf("schema", "kind", "username", "sHex", "vHex", "pass")
        val actual = buildSet { value.keys().forEachRemaining(::add) }
        if (actual != allowed) return null
        if (value.optInt("schema", -1) != SCHEMA) return null
        if (value.optString("kind") != KIND_SIGNUP) return null
        val username = value.optString("username")
        if (username.isEmpty()) return null
        if (runCatching { ServerRuntimeContract.requireAccountToken("username", username) }.isSuccess.not()) {
            return null
        }
        val sHex = value.optString("sHex")
        val vHex = value.optString("vHex")
        if (!isSrpHex(sHex) || !isSrpHex(vHex)) return null
        // An unlocked game takes any pass including empty; the proof check
        // only runs when the host set a game password.
        val pass = value.optString("pass")
        if (pass.length > 128) return null
        return SignupRequest(username, sHex, vHex, pass)
    }

    /** The LAN sign-up reply frame (the shared sign-up codes, this wire). */
    fun encodeSignupReply(reply: com.pocketrealm.discovery.iroh.SignupProtocol.Reply): String = JSONObject()
        .put("schema", SCHEMA)
        .put("kind", KIND_SIGNUP)
        .put("ok", reply.ok)
        .put("code", reply.code)
        .put("detail", reply.detail)
        .toString()

    /** Decodes a LAN sign-up reply; null = off-protocol. */
    fun decodeSignupReply(payload: ByteArray): com.pocketrealm.discovery.iroh.SignupProtocol.Reply? {
        val value = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return null
        if (value.optInt("schema", -1) != SCHEMA) return null
        if (value.optString("kind") != KIND_SIGNUP) return null
        val code = value.optString("code")
        if (code.isEmpty()) return null
        return com.pocketrealm.discovery.iroh.SignupProtocol.Reply(
            value.optBoolean("ok"),
            code,
            value.optString("detail"),
        )
    }

    /** 4-byte big-endian length prefix + the UTF-8 payload. */
    fun frame(json: String): ByteArray {
        val payload = json.toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_RESPONSE_BYTES.coerceAtLeast(MAX_REQUEST_BYTES)) {
            "frame payload exceeds the protocol cap"
        }
        val framed = ByteArray(4 + payload.size)
        framed[0] = (payload.size ushr 24).toByte()
        framed[1] = (payload.size ushr 16).toByte()
        framed[2] = (payload.size ushr 8).toByte()
        framed[3] = payload.size.toByte()
        payload.copyInto(framed, 4)
        return framed
    }

    /** Reads one length-prefixed frame; null on EOF, oversize, or timeout. */
    fun readFrame(input: InputStream, maxBytes: Int): ByteArray? {
        val header = readExactly(input, 4) ?: return null
        val length = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (length <= 0 || length > maxBytes) return null
        return readExactly(input, length)
    }

    private fun readExactly(input: InputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = runCatching { input.read(buffer, read, count - read) }.getOrNull() ?: return null
            if (n <= 0) return null
            read += n
        }
        return buffer
    }
}

/**
 * The friend-side one-shot client: derive `(s, v)` from the typed
 * password ON THIS DEVICE, send one framed request bound to the invite
 * token, and report the verdict. On any refusal the derived verifier is
 * dropped on the floor (design rule: on REJECTED the friend's device
 * discards the derived s/v immediately) — the password and its verifier
 * never persist past a refused request.
 */
class FriendAccessClient(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) {
    sealed class Verdict {
        /** The host accepted the request into its approval queue. */
        data class Submitted(val inviteId: String) : Verdict()

        /** Provisioning already completed at submit time. */
        data class Provisioned(val inviteId: String, val username: String, val accountId: Long) : Verdict()

        /** The host refused (unknown/expired/burned/locked/malformed) — one indistinct blob. */
        data class Refused(val detail: String = FriendProvisionProtocol.REFUSED_RESPONSE) : Verdict()

        /** The host could not be reached or answered off-protocol. */
        data class Unreachable(val reason: String) : Verdict()
    }

    /**
     * One request per connection. [host] is the same canonical IPv4 literal
     * the join flow already carries (the endpoint parser's rule —
     * keeps hostnames out until the parser decision lands).
     */
    @Suppress("TooGenericExceptionCaught") // every transport failure is the honest Unreachable verdict
    fun requestAccess(
        host: String,
        port: Int,
        inviteId: String,
        token: String,
        username: String,
        password: String,
    ): Verdict {
        // The shared realm rule; a malformed draft never leaves the device.
        runCatching { ServerRuntimeContract.requireAccountToken("username", username) }
            .getOrElse { return Verdict.Unreachable(it.message ?: "invalid username") }
        // The salt constraints live in Srp6AccountMath (32 bytes, both ends
        // nonzero) so realmd's challenge packet carries the unpadded form.
        val verifier = Srp6AccountMath.verifierFor(username, password)
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                val output: OutputStream = socket.getOutputStream()
                output.write(
                    FriendProvisionProtocol.encodeRequest(
                        inviteId, token, username, verifier.sHex, verifier.vHex,
                    ),
                )
                output.flush()
                val payload = FriendProvisionProtocol.readFrame(
                    socket.getInputStream(),
                    FriendProvisionProtocol.MAX_RESPONSE_BYTES,
                ) ?: return Verdict.Unreachable("host closed or timed out mid-response")
                val response = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
                    ?: return Verdict.Unreachable("unparseable host response")
                when {
                    response.optBoolean("ok") && response.optString("state") == "PROVISIONED" ->
                        Verdict.Provisioned(
                            response.optString("inviteId", inviteId),
                            response.optString("username", username),
                            response.optLong("accountId", 0L),
                        )
                    response.optBoolean("ok") -> Verdict.Submitted(response.optString("inviteId", inviteId))
                    else -> Verdict.Refused()
                }
            }
        } catch (failure: Throwable) {
            Verdict.Unreachable(failure.message ?: failure.javaClass.simpleName)
        }
        // The refused/unreachable paths drop `verifier` here: nothing derived
        // from the password outlives a refused request.
    }

    /** One LAN sign-up ask (D7) over the friend-access port. */
    sealed class SignupOutcome {
        /** The host answered with a sign-up verdict (created or a refusal). */
        data class Answered(val reply: com.pocketrealm.discovery.iroh.SignupProtocol.Reply) : SignupOutcome()

        /** The host could not be reached or answered off-protocol. */
        data class Unreachable(val reason: String) : SignupOutcome()
    }

    /**
     * The D7 LAN sign-up ask: derives the SRP6 verifier ON THIS DEVICE,
     * proves the game password (when the friend typed one) with the D3
     * proof pinned to the lane name, and reports the host's verdict. The
     * typed passwords never leave the device — only the verifier and the
     * proof cross.
     */
    @Suppress("TooGenericExceptionCaught") // every transport failure is the honest Unreachable verdict
    fun requestSignup(
        host: String,
        port: Int,
        username: String,
        password: String,
        gamePassword: String?,
    ): SignupOutcome {
        runCatching { ServerRuntimeContract.requireAccountToken("username", username) }
            .getOrElse { return SignupOutcome.Unreachable(it.message ?: "invalid username") }
        val verifier = Srp6AccountMath.verifierFor(username, password)
        val pass = gamePassword?.takeIf { it.isNotEmpty() }
            ?.let(FriendProvisionProtocol::lanSignupPass)
            ?: ""
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                socket.getOutputStream().apply {
                    write(
                        FriendProvisionProtocol.encodeSignupRequest(
                            username, verifier.sHex, verifier.vHex, pass,
                        ),
                    )
                    flush()
                }
                val payload = FriendProvisionProtocol.readFrame(
                    socket.getInputStream(),
                    FriendProvisionProtocol.MAX_RESPONSE_BYTES,
                ) ?: return SignupOutcome.Unreachable("host closed or timed out mid-response")
                val reply = FriendProvisionProtocol.decodeSignupReply(payload)
                    // The locked-source answer is the invite lane's identical
                    // refusal blob; the friend sees the shared rate-limit
                    // sentence, never an "unparseable" mystery.
                    ?: if (String(payload, Charsets.UTF_8) == FriendProvisionProtocol.REFUSED_RESPONSE) {
                        com.pocketrealm.discovery.iroh.SignupProtocol.RATE_LIMITED_REPLY
                    } else {
                        return SignupOutcome.Unreachable("unparseable host response")
                    }
                SignupOutcome.Answered(reply)
            }
        } catch (failure: Throwable) {
            SignupOutcome.Unreachable(failure.message ?: failure.javaClass.simpleName)
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 10_000
    }
}
