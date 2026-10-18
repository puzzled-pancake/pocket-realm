@file:Suppress("MagicNumber")
/*
 * MagicNumber suppression (reviewed): the inline byte masks/shifts and the
 * raw auth-lane struct sizes ARE the realmd wire format, documented against
 * the cited server sources (AuthSocket.cpp / AuthCodes.h / Common.h) and
 * RealmListRewrite's endianness note.
 */
package com.pocketrealm.discovery.iroh

import com.pocketrealm.client.ClientFlavor

/**
 * W6-N3: the join-side realmd stream filter — rides the JOINER gateway's
 * 3724 lane and rebuilds every SMSG_REALM_LIST to the joiner's own
 * loopback world target.
 *
 * WIRE REALITY (code-red R5-1, grounded in the shipped server sources the
 * layouts cite): the realmd auth lane is NOT a uniformly framed stream.
 * The logon responses are RAW structs with no size field:
 *  - the logon-challenge response (AuthSocket.cpp:489-543): [u8 cmd=0x00]
 *    [u8 err] [B 32] [u8 g_len] [g] [u8 N_len] [N] [s 32]
 *    [VersionChallenge 16, AuthSocket.cpp:187] [u8 securityFlags] plus the
 *    flag legs (PIN +20 / UNK +13 / AUTHENTICATOR +1, AuthSocket.cpp:53-56
 *    and :513-536);
 *  - the logon-proof response (AuthSocket.cpp:135-153, written raw at
 *    :305/:324): 26 bytes for the vanilla builds (cmd+err+M2 20+LoginFlags
 *    4) and 32 bytes for 2.x/3.x (cmd+err+M2 20+u32+u32+u16);
 *  - short raw error replies when auth fails (AuthSocket.cpp:185-188).
 * ONLY the realm-list response is size-prefixed ([u8 0x10][u16 LE size]
 * [payload], AuthSocket.cpp:884-893).
 *
 * The filter is a PHASE MACHINE whose invariant holds in EVERY phase
 * (code-red round 2 — the round-1 design's terminal PASSTHROUGH and its
 * belt byte-wise residual both relayed the host's raw address; neither
 * exists anymore):
 *  1. CHALLENGE — consume the computable-length raw challenge response;
 *     a foreign first byte or an upstream error reply hands the lane to
 *     the BELT scanner (nothing realm-list-shaped can ever reach the
 *     client unrewritten);
 *  2. PROOF — consume the flavor's fixed-size raw proof response (the
 *     size comes from the OWNING ClientFlavor registry), then arm the
 *     framed phase; a mismatched shape arms BELT;
 *  3. FRAMED — strict: ONLY realm-list frames flow post-auth. Every list
 *     is rewritten; a corrupt/oversized/foreign frame throws (the loud
 *     exit); a frame that does not complete within its declared byte
 *     budget is a misframed stream and throws (code-red R2-2-2: the
 *     wrong-flavor pairing stalled the client at logon);
 *  4. BELT — the fallback for unknown shapes: a validated scanner that
 *     REWRITES every realm-list candidate, and a candidate that FAILS the
 *     parse is realm-list-shaped corruption — the join dies loudly
 *     (code-red R3-r2-2: no byte-wise passthrough residual). Raw auth
 *     bytes (never 0x10-headed) pass byte-by-byte — they cannot carry a
 *     realm list.
 *
 * Failure posture: a [RealmListRewrite.RealmListRewriteException] out of
 * [feed] is the gateway's GATEWAY-REALMLIST-REWRITE-FAILED loud exit.
 * The client→realmd direction needs no filter (nothing address-shaped
 * flows that way) — the gateway pumps it raw.
 */
class AuthStreamFilter(private val flavor: ClientFlavor) {

    /** The accumulation buffer (messages arrive in arbitrary TCP slices). */
    private var buffer: ByteArray = ByteArray(0)

    /** The pending frame's declared payload size, or -1 while the header
     * itself is still incomplete (FRAMED/BELT phases). */
    private var pendingSize: Int = -1

    private var phase: Phase = Phase.CHALLENGE

    private enum class Phase { CHALLENGE, PROOF, FRAMED, BELT }

    /** Total bytes fed (the framed-stall budget's odometer, R2-2-2). */
    private var totalBytesSeen: Long = 0

    /** The odometer reading when the pending frame's header armed. */
    private var framedArmedAtTotal: Long = 0

    /**
     * Feeds one chunk of the realmd→client stream; returns the bytes to
     * write to the client, in order (zero or more complete chunks). A
     * chunk may contain partial messages, multiple messages, or both.
     */
    /**
     * Feeds one chunk of the realmd→client stream; returns the bytes to
     * write to the client, in order (zero or more complete chunks). A
     * chunk may contain partial messages, multiple messages, or both.
     * With [eof] the held tail is drained verbatim EXCEPT a held REALM-
     * LIST-shaped candidate — the frame the invariant protects: it is
     * validated-and-rewritten, or the filter throws the loud refusal
     * (a dropped/relayed realm list at EOF would ship the host's raw
     * address — never that). */
    // contract: the phase dispatch drains until a phase reports
    // incomplete — one exit per drain pass, plus the EOF tail leg.
    @Suppress("ReturnCount")
    fun feed(chunk: ByteArray, eof: Boolean = false): List<ByteArray> {
        if (chunk.isEmpty() && !eof) return emptyList()
        if (chunk.isNotEmpty()) {
            buffer = if (buffer.isEmpty()) chunk else buffer + chunk
            totalBytesSeen += chunk.size
        }
        preAuthBudgetGuard()
        val emitted = ArrayList<ByteArray>(2)
        while (true) {
            // A phase reporting incomplete (null) HOLDS the buffer for the
            // next feed — only the EOF leg may drain the held tail (code-
            // red round 2: draining on every wait passed the whole stream
            // through byte-wise, breaking the invariant).
            val out = when (phase) {
                Phase.CHALLENGE -> consumeChallenge()
                Phase.PROOF -> consumeProof()
                Phase.FRAMED -> framedChunk()
                Phase.BELT -> beltChunk()
            } ?: break
            emitted += out
        }
        if (eof) emitted += drainEofTail()
        return emitted
    }

    /** Code-red round-6 R1-r6-1: the PRE-AUTH phases get the same
     * stall/budget treatment the framed phase already has — a CHALLENGE
     * needs at most ~600 bytes and a PROOF at most ~32, so a stream that
     * never emits a valid challenge must not grow the child's heap
     * without bound. The budget covers the whole pre-auth window (both
     * phases), the buffer AND every byte seen. (Own function: feed's
     * cyclomatic budget.) */
    private fun preAuthBudgetGuard() {
        // The window: the pre-auth phases, PLUS the belt before any valid
        // challenge was ever consumed (a hostile flood arms the belt on
        // its first foreign byte — that lane must budget too, while a
        // legitimate long post-auth stream, which HAS seen its challenge,
        // never triggers this).
        val preAuth = phase == Phase.CHALLENGE || phase == Phase.PROOF ||
            !challengeSeen
        if (!preAuth) return
        val overBudget = buffer.size > MAX_PRE_AUTH_BYTES ||
            totalBytesSeen > MAX_PRE_AUTH_BYTES
        if (overBudget) {
            throw RealmListRewrite.RealmListRewriteException(
                "VAL-IROH-REALMLIST: the pre-auth phase exceeded its " +
                    "$MAX_PRE_AUTH_BYTES-byte budget (" + phase.name +
                    ") — the stream is hostile or misframed",
            )
        }
    }

    /** The EOF leg: everything still buffered passes through untouched EXCEPT
     * a held REALM-LIST-shaped candidate — the frame the invariant
     * protects. It is validated-and-rewritten, or the filter throws the
     * loud refusal (a dropped/relayed realm list at EOF would ship the
     * host's raw address — never that). Round-5 R2-r5-1: the shape test is
     * the OPCODE alone, at ANY tail length — the old `size > header`
     * conjunct misclassified a header-only frame ([0x10,szLo,szHi]) as
     * inert and relayed it verbatim, leaving the client hanging at logon
     * on a short read while the gateway stayed READY; a truncated
     * realm-list header is exactly as loud as a corrupt one. */
    private fun drainEofTail(): List<ByteArray> {
        if (buffer.isEmpty()) return emptyList()
        val rest = buffer
        buffer = ByteArray(0)
        pendingSize = -1
        val realmListShaped = rest[0].toInt() and BYTE_MASK == RealmListRewrite.CMD_REALM_LIST
        // Realm-list-shaped: validated-and-rewritten, or the loud refusal
        // — never relayed with the host's raw address.
        return listOf(
            if (realmListShaped) {
                RealmListRewrite.rewriteUnderAnyLayout(
                    rest, flavor, RealmListRewrite.JOINER_WORLD_TARGET,
                )
            } else {
                rest
            },
        )
    }

    /** Whether a valid challenge was ever consumed (the pre-auth budget
     * window closes once it has — round-6 R1-r6-1). */
    private var challengeSeen: Boolean = false

    /** The raw bytes still held for an in-flight message (the gateway's
     * EOF leg decision reads this). */
    fun hasPendingBytes(): Boolean = buffer.isNotEmpty()

    // ------------------------------------------------------------- phases

    /**
     * The logon-challenge response (shape cited in the class doc). The
     * length is computable: fixed head + variable g/N (their lengths ride
     * the wire) + fixed salt/seed + the security-flags legs. Complete →
     * emit RAW and advance to the proof phase. A foreign first byte or an
     * upstream error reply hands the lane to the BELT scanner — nothing
     * realm-list-shaped may ever reach the client unrewritten (code-red
     * R7-N3-1/R2-2-1).
     */
    // contract: one leg per outcome (wait / foreign-flip / error-flip /
    // incomplete / complete) — the ladder IS the wire grammar.
    @Suppress("ReturnCount")
    private fun consumeChallenge(): List<ByteArray>? {
        if (buffer.size < 2) return null
        if (buffer[0].toInt() and BYTE_MASK != CMD_LOGON_CHALLENGE) {
            phase = Phase.BELT
            pendingSize = -1
            return emptyList()
        }
        if (buffer[1].toInt() and BYTE_MASK != LOGON_SUCCESS) {
            val reply = buffer.copyOfRange(0, 2)
            buffer = buffer.copyOfRange(2, buffer.size)
            phase = Phase.BELT
            pendingSize = -1
            return listOf(reply)
        }
        if (buffer.size < CHALLENGE_FIXED_HEAD) return null
        var offset = 2 + B_BYTES
        val gLen = buffer[offset].toInt() and BYTE_MASK
        offset += 1 + gLen
        if (buffer.size < offset + 1) return null
        val nLen = buffer[offset].toInt() and BYTE_MASK
        offset += 1 + nLen
        val flagsOffset = offset + SALT_BYTES + SEED_BYTES
        if (buffer.size < flagsOffset + 1) return null
        val needed = flagsOffset + 1 +
            securityLegs(buffer[flagsOffset].toInt() and BYTE_MASK)
        if (buffer.size < needed) return null
        val challenge = buffer.copyOfRange(0, needed)
        buffer = buffer.copyOfRange(needed, buffer.size)
        phase = Phase.PROOF
        challengeSeen = true
        return listOf(challenge)
    }

    /**
     * The logon-proof response: FIXED size per flavor — from the OWNING
     * [ClientFlavor] registry (Correction C3, verified against
     * AuthSocket.cpp:135-153 at S8; code-red R2-1: never a restated
     * constant). It self-identifies ([0x01][0x00]) — a mismatch means the
     * challenge parse ended off-position (an unknown build/account shape):
     * the BELT scanner takes over (the misalignment is logged on stderr —
     * the gateway surfaces it). A match arms the strict framed phase
     * where the LOUD rewrite refusal lives.
     */
    // contract: shape mismatch → BELT flip; incomplete → wait; match →
    // emit — one exit per leg.
    @Suppress("ReturnCount")
    private fun consumeProof(): List<ByteArray>? {
        val size = requireNotNull(flavor.proofResponseBytes) {
            "flavor ${flavor.flavorId} declares no proof-response size"
        }
        if (buffer.size < size) return null
        val isProof = buffer[0].toInt() and BYTE_MASK == CMD_LOGON_PROOF &&
            buffer[1].toInt() and BYTE_MASK == LOGON_SUCCESS
        if (!isProof) {
            System.err.println(
                "gateway filter: proof response did not match the " +
                    "${flavor.flavorId} layout — falling back to the " +
                    "validated-rewrite belt",
            )
            phase = Phase.BELT
            pendingSize = -1
            return emptyList() // keep draining this chunk through the belt
        }
        val proof = buffer.copyOfRange(0, size)
        buffer = buffer.copyOfRange(size, buffer.size)
        phase = Phase.FRAMED
        return listOf(proof)
    }

    /**
     * The strict framed phase: post-auth realmd sends ONLY realm-list
     * frames — every list is rewritten, and anything else is a misframed
     * stream that throws (the loud exit beats consuming garbage frames or
     * stalling the client at logon; code-red R2-2-2). A frame that does
     * not complete within its declared byte budget is equally a loud
     * refusal (never a lane-stalling wait).
     */
    // contract: zero-size / oversize / foreign-opcode / budget / emit —
    // one throw per distinct wire violation, one exit per leg.
    @Suppress("ReturnCount", "ThrowsCount")
    private fun framedChunk(): List<ByteArray>? {
        if (pendingSize < 0) {
            if (buffer.size < RealmListRewrite.FRAME_HEADER_BYTES) return null
            val opcode = buffer[0].toInt() and BYTE_MASK
            // The realmd size field is u16 LITTLE-ENDIAN (see
            // RealmListRewrite's endianness doc).
            val size = (buffer[1].toInt() and BYTE_MASK) or
                ((buffer[2].toInt() and BYTE_MASK) shl 8)
            if (RealmListRewrite.isRealmListOpcode(opcode) && size == 0) {
                throw RealmListRewrite.RealmListRewriteException(
                    "VAL-IROH-REALMLIST: zero-size realm list frame",
                )
            }
            if (RealmListRewrite.isRealmListOpcode(opcode) &&
                size > MAX_FRAMED_PAYLOAD
            ) {
                throw RealmListRewrite.RealmListRewriteException(
                    "VAL-IROH-REALMLIST: realm list declares $size payload " +
                        "bytes — beyond the pinned post-auth bound",
                )
            }
            if (!RealmListRewrite.isRealmListOpcode(opcode)) {
                throw RealmListRewrite.RealmListRewriteException(
                    "VAL-IROH-REALMLIST: opcode 0x${opcode.toString(16)} is " +
                        "not a realm list in the framed phase — the stream " +
                        "is misframed (wrong-flavor pairing?)",
                )
            }
            pendingSize = size
            framedArmedAtTotal = totalBytesSeen
        }
        val frameBytes = RealmListRewrite.FRAME_HEADER_BYTES + pendingSize
        if (buffer.size < frameBytes) {
            // R2-2-2: a frame that exceeds its declared payload budget
            // without completing means the stream is MISFRAMED — the stall
            // would hang the client at logon; refuse loudly instead.
            if (totalBytesSeen - framedArmedAtTotal > MAX_FRAMED_PAYLOAD) {
                throw RealmListRewrite.RealmListRewriteException(
                    "VAL-IROH-REALMLIST: a framed message exceeded its " +
                        "declared payload budget — the stream is misframed " +
                        "(wrong-flavor pairing?)",
                )
            }
            return null
        }
        val frame = buffer.copyOfRange(0, frameBytes)
        buffer = buffer.copyOfRange(frameBytes, buffer.size)
        pendingSize = -1
        return if (RealmListRewrite.isRealmListOpcode(
                frame[0].toInt() and BYTE_MASK,
            )
        ) {
            // Round-6 R6-2-2: the framed phase uses the SAME layout
            // contract as the belt — the declared flavor rewrites, a
            // foreign-layout frame refuses as misdeclared (never a
            // cross-layout re-emit).
            listOf(
                RealmListRewrite.rewriteUnderAnyLayout(
                    frame, flavor, RealmListRewrite.JOINER_WORLD_TARGET,
                ),
            )
        } else {
            listOf(frame)
        }
    }

    /**
     * The BELT scanner (unknown-shape fallback): a validated scanner that
     * REWRITES every realm-list candidate, and a candidate that FAILS the
     * parse is realm-list-shaped corruption — the join dies loudly
     * (code-red R3-r2-2: no byte-wise passthrough residual). Raw auth
     * bytes (never 0x10-headed) pass byte-by-byte.
     */
    // contract: non-list → 1 byte; oversize/zero → loud; incomplete →
    // wait; valid → rewrite — one exit per leg.
    @Suppress("ReturnCount")
    private fun beltChunk(): List<ByteArray>? {
        if (buffer.size < RealmListRewrite.FRAME_HEADER_BYTES) return null
        if (buffer[0].toInt() and BYTE_MASK != RealmListRewrite.CMD_REALM_LIST) {
            // Bulk-drain every byte up to the NEXT 0x10 head in one copy
            // (R4-3-1/R3-1: per-byte copyOfRange was quadratic under a
            // hostile flood and wedged the lane with no rejection).
            val next = indexOfByte(buffer, 1, RealmListRewrite.CMD_REALM_LIST)
            val drainTo = if (next < 0) buffer.size else next
            val drained = buffer.copyOfRange(0, drainTo)
            buffer = buffer.copyOfRange(drainTo, buffer.size)
            return listOf(drained)
        }
        val size = (buffer[1].toInt() and BYTE_MASK) or
            ((buffer[2].toInt() and BYTE_MASK) shl 8)
        if (size == 0) {
            throw RealmListRewrite.RealmListRewriteException(
                "VAL-IROH-REALMLIST: zero-size realm list frame",
            )
        }
        if (size > MAX_FRAMED_PAYLOAD) {
            throw RealmListRewrite.RealmListRewriteException(
                "VAL-IROH-REALMLIST: realm list declares $size payload " +
                    "bytes — beyond the pinned bound",
            )
        }
        val frameBytes = RealmListRewrite.FRAME_HEADER_BYTES + size
        if (buffer.size < frameBytes) return null
        val candidate = buffer.copyOfRange(0, frameBytes)
        // The declared flavor parses first; a hostile/misconfigured host
        // may speak ANOTHER flavor's layout — the belt tries every
        // registered layout so a valid list is ALWAYS rewritten (never
        // relayed with the host's address), and a candidate that parses
        // under NO layout is corruption: the join dies loudly (R3-r2-2).
        val rewritten = RealmListRewrite.rewriteUnderAnyLayout(
            candidate, flavor, RealmListRewrite.JOINER_WORLD_TARGET,
        )
        buffer = buffer.copyOfRange(frameBytes, buffer.size)
        return listOf(rewritten)
    }

    /** The u8 mask for reading one wire byte, the auth lane's raw
     * commands, and the post-auth payload bound (all cited in the class
     * doc). The proof-response SIZES live on the owning [ClientFlavor]. */
    private companion object {
        const val BYTE_MASK = 0xff

        /** The first index of [byte] in [data] at/after [from], or -1. */
        fun indexOfByte(data: ByteArray, from: Int, byte: Int): Int {
            for (i in from until data.size) {
                if (data[i].toInt() == byte) return i
            }
            return -1
        }

        const val CMD_LOGON_CHALLENGE = 0x00
        const val CMD_LOGON_PROOF = 0x01
        const val LOGON_SUCCESS = 0x00

        const val B_BYTES = 32

        /** cmd + err + B(32) = 34 bytes; g_len lives at index 34, so the
         * parse needs 35 buffered bytes before reading it. */
        const val CHALLENGE_FIXED_HEAD = 3 + B_BYTES
        const val SALT_BYTES = 32
        const val SEED_BYTES = 16

        /** The post-auth realm-list payload bound (a dozens-of-entries
         * list is a few KB; a declared size beyond this is a loud refusal
         * in both the framed and belt phases). */
        const val MAX_FRAMED_PAYLOAD = 16 * 1024

        /** The PRE-AUTH budget (round-6 R1-r6-1): a challenge needs ≤~600
         * bytes and a proof ≤~32 — 64 KiB is two orders of headroom, and
         * beyond it the pre-auth window is a heap-growth attack, not a
         * session. */
        const val MAX_PRE_AUTH_BYTES = 64 * 1024

        /** Security-flag legs (AuthSocket.cpp:53-56 and :513-536). */
        const val SECURITY_FLAG_PIN = 0x01
        const val SECURITY_FLAG_UNK = 0x02
        const val SECURITY_FLAG_AUTHENTICATOR = 0x04

        fun securityLegs(flags: Int): Int {
            var bytes = 0
            if (flags and SECURITY_FLAG_PIN != 0) bytes += 4 + 16
            if (flags and SECURITY_FLAG_UNK != 0) bytes += 5 + 8
            if (flags and SECURITY_FLAG_AUTHENTICATOR != 0) bytes += 1
            return bytes
        }
    }
}
