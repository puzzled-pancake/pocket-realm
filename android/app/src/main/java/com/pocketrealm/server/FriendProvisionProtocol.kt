package com.pocketrealm.server

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The remote-account provisioning wire (design-remote-accounts §5/§6):
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

    /** Hard cap on one framed request, in bytes (§6). */
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
     * the join flow already carries (the endpoint parser's rule — §13.5
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

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 10_000
    }
}
