@file:Suppress("MagicNumber")
/*
 * MagicNumber suppression (reviewed): this file IS a wire-format
 * transcription — the byte widths, shift/mask pairs, and layout constants
 * are the realm-list packet's fields, each documented in the class doc
 * against its citing source line (AuthSocket.cpp / RealmList.cpp /
 * ByteBuffer.h / Common.h). Naming them would restate the layout tables
 * the doc already owns byte-for-byte.
 */
package com.pocketrealm.discovery.iroh

import com.pocketrealm.client.ClientFlavor
import com.pocketrealm.client.ClientFlavors

/**
 * W6-N3 (NOSTR-IROH-BUILD-PLAN.md stage 3's parse+rebuild correction): the
 * joiner gateway's SMSG_REALM_LIST rewriter.
 *
 * The world endpoint rides the auth (realmd, 3724) lane as a C-string
 * "address:port" inside the realm-list packet, and the packet layout
 * DIFFERS across the three flavors — a same-length byte swap is not enough
 * because the rebuilt address is a different LENGTH than the host's: the
 * u16 size field in the realmd frame header must be RECOMPUTED, and the
 * count field's width itself differs by flavor. The layouts below are
 * transcribed from the SHIPPED server sources this wave; every field cites
 * its source line:
 *
 * Frame (all flavors, realmd AuthSocket.cpp `_HandleRealmList`, e.g.
 * native/cmangos/src/realmd/AuthSocket.cpp:889-893):
 *   [u8 opcode=0x10][u16 LE payload size][payload]
 *
 * NUMERIC ENDIANNESS: every numeric field here is LITTLE-ENDIAN on the
 * wire — the mangos ByteBuffer appends raw memory
 * (native/cmangos/src/shared/Util/ByteBuffer.h:466 `append((uint8*)&value,
 * sizeof(value))` — the template append at :462-468; the :115 cite was
 * wrong and the cite pin holds this line honest) and EndianConvert is a NO-OP on little-endian hosts
 * (native/cmangos/src/shared/Util/ByteConverter.h:49-53 with the
 * Platform/Define.h x86 default), so the realmd packet's size field,
 * header word, count, population float, build, and trailer are all LE.
 * The C-strings are raw bytes + 0x00 either way.
 *
 * Payload — 1.12 (the vanilla case, AuthSocket.cpp:895-955):
 *   u32 unused(0), u8 realmCount,
 *   per realm: u32 icon, u8 realmFlags, CStr name, CStr address,
 *              f32 population, u8 characterCount, u8 categoryId, u8 unk,
 *   u16 trailer(0x0002)
 *
 * Payload — 2.4.3 AND 3.3.5 (the tbc/wotlk case, AuthSocket.cpp:961-1032;
 * the branches are byte-identical in the cmangos-tbc and cmangos-wotlk
 * trees' AuthSocket.cpp — same source, same layout):
 *   u32 unused(0), u16 realmCount,
 *   per realm: u8 icon, u8 lock, u8 realmFlags, CStr name, CStr address,
 *              f32 population, u8 characterCount, u8 categoryId, u8 unk,
 *              [flags & SPECIFYBUILD: u8 major, u8 minor, u8 bugfix, u16 build]
 *   u16 trailer(0x0010)
 *
 * CStr = the mangos ByteBuffer std::string append
 * (native/cmangos/src/shared/Util/ByteBuffer.h:173): raw bytes + one 0x00.
 * realmd appends the port to the DB address itself
 * (native/cmangos/src/realmd/RealmList.cpp:155-158), so the wire C-string
 * is always "host:port".
 *
 * THE PRIVACY RULE (the ask's invariant): the rewrite happens ONLY in the
 * joiner gateway, to the joiner's own loopback world listener — the HOST's
 * realmlist row is never touched, and a frame this walker cannot fully
 * account for is a LOUD refusal (the gateway exits
 * GATEWAY-REALMLIST-REWRITE-FAILED) rather than a pass-through that would
 * send the client dialing the host's raw address.
 */
object RealmListRewrite {

    /** CMD_REALM_LIST (realmd AuthCodes.h:34, all three flavors). */
    const val CMD_REALM_LIST: Int = 0x10

    /** REALM_FLAG_SPECIFYBUILD (native/cmangos/src/shared/Common.h:137). */
    const val REALM_FLAG_SPECIFYBUILD: Int = 0x04

    /** The realmd frame header: opcode byte + u16 LE payload size. */
    const val FRAME_HEADER_BYTES: Int = 3

    /** Population is an f32; chars/category/unk are one byte each. */
    private const val ENTRY_FIXED_TAIL_BYTES: Int = 4 + 1 + 1 + 1

    /** The SPECIFYBUILD block: u8 major + u8 minor + u8 bugfix + u16 build. */
    private const val BUILD_BLOCK_BYTES: Int = 3 + 2

    /** The per-flavor trailer constants (AuthSocket.cpp's trailing u16s). */
    private const val TRAILER_VANILLA: Int = 0x0002
    private const val TRAILER_MODERN: Int = 0x0010

    /** The u8 the 1.12 client renders from the u32 icon field. */
    private const val ICON_MAX: Long = 0xffL

    /** The u16 size field's ceiling (the frame's only length declaration). */
    private const val U16_MAX: Int = 0xffff

    /**
     * The rebuild target: the joiner gateway's own loopback world listener.
     * The CI assertion pins that the rebuilt list is EXACTLY what the
     * client would dial next.
     */
    const val JOINER_WORLD_TARGET: String = "127.0.0.1:${GatewayContract.PORT_WORLD}"

    /** One parsed realm entry — the fields the rewrite touches. */
    data class RealmEntry(
        val name: String,
        val address: String,
        val specifyBuild: Boolean,
    )

    /** A fully parsed realm-list frame: every byte accounted for. */
    data class ParsedRealmList(
        val opcode: Int,
        val payloadSize: Int,
        val entries: List<RealmEntry>,
    )

    /**
     * True when [opcode] is the realm-list opcode (the stream filter's
     * routing decision — every other frame passes through untouched).
     */
    fun isRealmListOpcode(opcode: Int): Boolean = opcode == CMD_REALM_LIST

    /**
     * True when [flavor]'s realmd speaks the 1.12 realm-list shape (u8
     * count, u32 icon, no lock byte, 0x0002 trailer). Keyed against the
     * OWNING registry's members — an unregistered flavor refuses (there is
     * no fourth layout to guess).
     */
    fun isVanillaLayout(flavor: ClientFlavor): Boolean = flavor == ClientFlavors.VANILLA

    /**
     * The expected layout for [flavorId] (the gateway's --flavor argument),
     * resolved through the owning registry. Throws VAL-IROH-REALMLIST for
     * an unknown id — fail-closed, never a default layout.
     */
    fun flavorFor(id: String): ClientFlavor =
        ClientFlavors.ALL.firstOrNull { it.flavorId == id }
            ?: throw RealmListRewriteException(
                "VAL-IROH-REALMLIST: unknown flavor '$id' (expected one of " +
                    ClientFlavors.ALL.joinToString(", ") { it.flavorId } + ")",
            )

    /**
     * Parses ONE complete realmd frame ([frame]: opcode byte + u16 LE size
     * + payload, exactly the bytes realmd wrote). Structural per [flavor];
     * EVERY payload byte is consumed and every field is shape-validated —
     * a truncated field, an unterminated C-string, or a wrong trailer
     * throws [RealmListRewriteException] (VAL-IROH-REALMLIST). There is
     * deliberately no best-effort parse: a frame we cannot fully account
     * for is a frame we must not rewrite.
     */
    fun parse(frame: ByteArray, flavor: ClientFlavor): ParsedRealmList {
        val entries = ArrayList<RealmEntry>()
        // pinned-ordering law: the count bound fires BEFORE the entry
        // walk under BOTH readings — the bound is the stable, non-
        // attacker-influenceable refusal for crafted cross-flavor frames.
        walk(frame, flavor) { _, name, address, specifyBuild, _ ->
            entries.add(RealmEntry(name, address, specifyBuild))
        }
        // The count lives in the header walk; re-derive it from the entries
        // the walker produced (the size field is u16 LE).
        val opcode = frame[0].toInt() and 0xff
        val size = (frame[1].toInt() and 0xff) or ((frame[2].toInt() and 0xff) shl 8)
        return ParsedRealmList(opcode, size, entries)
    }

    /**
     * Rewrites [frame] under [flavor]'s layout — or refuses LOUDLY. The
     * round-6 R6-2-2 contract replaces the old cross-layout re-emit: a
     * frame that parses under a NON-declared layout is the MISDECLARED-
     * HOST signal (the listing names flavor X but the realmd speaks
     * layout Y), and re-emitting it under Y ships the declared client a
     * frame its naive field walk reads as a garbage dial target (the
     * probe dialled the realm NAME string). So: the declared layout
     * parses -> rewrite under it; a foreign layout parses -> the loud
     * refusal (never a foreign-layout re-emit); nothing parses -> the
     * corruption refusal. Either way the client never receives a frame
     * this filter cannot vouch for. */
    fun rewriteUnderAnyLayout(
        frame: ByteArray,
        flavor: ClientFlavor,
        newAddress: String,
    ): ByteArray = try {
        rewrite(frame, flavor, newAddress)
    } catch (declined: RealmListRewriteException) {
        val foreign = ClientFlavors.ALL.firstOrNull { candidate ->
            candidate != flavor &&
                runCatching { parse(frame, candidate) }.isSuccess
        } ?: throw declined
        throw RealmListRewriteException(
            "VAL-IROH-REALMLIST: the realm list parses under the " +
                foreign.flavorId + " layout but the join declares " +
                flavor.flavorId + " - the host's realmlist layout is " +
                "misdeclared; refusing instead of re-emitting a frame " +
                "the declared client would misread (R6-2-2)",
        )
    }

    /**
     * Rewrites ONE complete realm-list frame: a single validated walk that
     * copies every byte verbatim EXCEPT each entry's address C-string,
     * which becomes [newAddress] (the joiner's own loopback target); the
     * header's u16 size is recomputed for the new payload length. Counts,
     * names, flags, populations, and the trailer are preserved exactly —
     * the ONLY deltas are the address strings and the derived sizes.
     */
    fun rewrite(frame: ByteArray, flavor: ClientFlavor, newAddress: String): ByteArray {
        val out = Writer()
        walk(frame, flavor, sink = out, replacementAddress = newAddress)
        return out.toByteArray()
    }

    /**
     * The one layout walker. [sink] null → parse-only; [sink] set → every
     * verbatim byte region is ALSO written to the sink, with the address
     * C-string replaced by [replacementAddress]. Both modes consume the
     * whole frame and refuse anything unaccounted. (The per-entry walkers
     * carry the flavor's field layout; this orchestrator owns header,
     * count, loop, and trailer.)
     */
    private inline fun walk(
        frame: ByteArray,
        flavor: ClientFlavor,
        sink: Writer? = null,
        replacementAddress: String = "",
        onEntry: (index: Int, name: String, address: String, specifyBuild: Boolean, flags: Int) -> Unit =
            { _, _, _, _, _ -> },
    ) {
        val vanilla = isVanillaLayout(flavor)
        val r = Reader(frame)
        val count = walkFraming(r, sink, vanilla)
        repeat(count) { index ->
            walkEntry(EntryContext(r, sink, vanilla, index, replacementAddress), onEntry)
        }
        // The flavor's trailer constant (1.12: 0x0002; 2.x/3.x: 0x0010 —
        // the "why" comments are upstream's), validated and copied verbatim.
        val expectedTrailer = if (vanilla) TRAILER_VANILLA else TRAILER_MODERN
        val trailer = r.u16("trailer")
        if (trailer != expectedTrailer) {
            fail("trailer 0x${trailer.toString(16)} does not match the " +
                "${flavor.flavorId} layout (expected 0x${expectedTrailer.toString(16)})")
        }
        if (sink != null) sink.u16(trailer)
        if (!r.atEnd) fail("${r.remaining} unparsed bytes after the trailer")
        if (sink != null) sink.fillReservedSizeField(sink.size - FRAME_HEADER_BYTES)
    }

    /** The realmd frame header (opcode must be the realm list; the u16 LE
     * size must match the bytes present; the header word pins to zero —
     * all copied verbatim, the sink's size slot getting the REBUILT
     * length at the end) plus the flavor's count field width (1.12: u8;
     * 2.x/3.x: u16), bounded by the pinned entry cap. Returns the count. */
    private fun walkFraming(r: Reader, sink: Writer?, vanilla: Boolean): Int {
        val opcode = r.u8("opcode")
        if (!isRealmListOpcode(opcode)) {
            fail("frame opcode 0x${opcode.toString(16)} is not a realm list")
        }
        val payloadSize = r.u16("payload size")
        val expectedSize = r.totalBytes - FRAME_HEADER_BYTES
        if (payloadSize != expectedSize) {
            fail("declared payload size $payloadSize does not match the " +
                "$expectedSize payload bytes present")
        }
        val unused = r.u32("header word")
        if (unused != 0L) fail("realm list header word is $unused, expected 0")
        val count = if (vanilla) r.u8("realm count") else r.u16("realm count")
        if (count > MAX_REALM_ENTRIES) {
            throw CountExceedsBoundException(
                "VAL-IROH-REALMLIST: realm count $count exceeds the pinned bound",
            )
        }
        if (sink != null) {
            sink.u8(opcode)
            sink.reserveSizeField()
            sink.u32(0L)
            if (vanilla) sink.u8(count) else sink.u16(count)
        }
        return count
    }

    /** ONE realm entry per flavor's field layout: the head fields, the
     * two C-strings (name kept, address replaced in the sink), the fixed
     * tail, and the optional SPECIFYBUILD block (2.x/3.x only — on 1.12
     * the flag bit is LEGAL but names no block: upstream appends the
     * version to the realm NAME instead, AuthSocket.cpp:934-938, and
     * writes the flags byte with the bit still set at :948). The name
     * C-string is copied BYTE-VERBATIM in the sink (code-red R2-5: a
     * US-ASCII re-encode mangled every byte >= 0x80 — a UTF-8 realm name
     * displayed corrupted on the joiner). */
    private inline fun walkEntry(
        ctx: EntryContext,
        onEntry: (index: Int, name: String, address: String, specifyBuild: Boolean, flags: Int) -> Unit,
    ) {
        val entryTag = "entry ${ctx.index}"
        val flags = ctx.head()
        val nameBytes = ctx.r.cStringBytes("$entryTag name")
        val address = ctx.r.cString("$entryTag address")
        validateAddressShape(address) { reason -> fail("$entryTag: $reason") }
        if (ctx.sink != null) {
            ctx.sink.rawCString(nameBytes)
            ctx.sink.cString(ctx.replacementAddress)
        }
        val tail = ctx.r.raw(ENTRY_FIXED_TAIL_BYTES, "$entryTag population/counts")
        if (ctx.sink != null) ctx.sink.raw(tail)
        val specifyBuild = ctx.buildBlock(flags, entryTag)
        onEntry(ctx.index, nameBytes.toString(Charsets.US_ASCII), address, specifyBuild, flags)
    }

    /** One entry's read/copy context: which flavor's head layout applies,
     * where the verbatim bytes land, and which entry is being walked. */
    private class EntryContext(
        val r: Reader,
        val sink: Writer?,
        val vanilla: Boolean,
        val index: Int,
        val replacementAddress: String,
    ) {
        /** The flavor's head fields and flags: 1.12 rides u32 icon + u8
         * flags (the client renders the low byte); 2.x/3.x rides u8 icon,
         * u8 lock, u8 flags. */
        fun head(): Int {
            val entryTag = "entry $index"
            return when {
                vanilla -> {
                    val icon = r.u32("$entryTag icon")
                    if (icon > ICON_MAX) {
                        fail("$entryTag: icon 0x${icon.toString(16)} exceeds the " +
                            "u8 the 1.12 client renders")
                    }
                    if (sink != null) sink.u32(icon)
                    r.u8("$entryTag flags").also { if (sink != null) sink.u8(it) }
                }

                else -> {
                    val icon = r.u8("$entryTag icon")
                    val lock = r.u8("$entryTag lock")
                    if (sink != null) {
                        sink.u8(icon)
                        sink.u8(lock)
                    }
                    r.u8("$entryTag flags").also { if (sink != null) sink.u8(it) }
                }
            }
        }

        /** The SPECIFYBUILD block: 2.x/3.x only. On 1.12 the flag bit is
         * LEGAL and names no block — upstream appends the version to the
         * realm NAME instead and writes the flags byte with the bit still
         * set (AuthSocket.cpp:934-938 + :948; code-red R5-3: refusing the
         * bit broke vanilla joins for any host with the show-version
         * realmlist config). */
        fun buildBlock(flags: Int, @Suppress("UNUSED_PARAMETER") entryTag: String): Boolean =
            when {
                // 1.12: the bit is legal; the version rides the NAME (no
                // block on the wire).
                vanilla -> true
                // 2.x/3.x: the block rides verbatim only when the flag is set.
                (flags and REALM_FLAG_SPECIFYBUILD) == 0 -> false
                else -> {
                    val buildBlock = r.raw(BUILD_BLOCK_BYTES, "$entryTag build block")
                    if (sink != null) sink.raw(buildBlock)
                    true
                }
            }
    }

    /** Sanity bound (the admission-fence posture: counts are untrusted). */
    const val MAX_REALM_ENTRIES: Int = 64

    /** The pinned entry-count bound fired (the bound-first
     * ordering is pinned by the gateway count-collision probe — the
     * refusal reason is stable across both flavors' readings). */
    class CountExceedsBoundException(message: String) :
        RealmListRewriteException(message)

    open class RealmListRewriteException(message: String) : IllegalStateException(message)

    /**
     * The address C-string must be "host:port" with a 1..5-digit port —
     * the shape realmd itself produces (RealmList.cpp:155-158 appends
     * ":port"). The HOST part is validated only for non-emptiness and
     * printability: the joiner accepts whatever the host advertised (LAN
     * IP, tunnel CGNAT literal, numeric public IP — the value's flavor is
     * the host's business; the JOINER never dials it, only replaces it).
     * A malformed address is a loud refusal, never a guessed rewrite.
     */
    fun validateAddressShape(address: String, fail: (String) -> Nothing) {
        validateAddressShapeLadder(address, fail)
    }

    // ----------------------------------------------------------- helpers

    /** Little-endian reader (the wire order; see the module doc) with
     * per-field failure attribution. */
    private class Reader(private val data: ByteArray) {
        var pos: Int = 0
            private set

        val atEnd: Boolean get() = pos == data.size
        val remaining: Int get() = data.size - pos
        val totalBytes: Int get() = data.size

        fun u8(field: String): Int {
            if (remaining < 1) fail("truncated $field")
            return data[pos++].toInt() and 0xff
        }

        fun u16(field: String): Int {
            if (remaining < 2) fail("truncated $field")
            val v = (data[pos].toInt() and 0xff) or ((data[pos + 1].toInt() and 0xff) shl 8)
            pos += 2
            return v
        }

        fun u32(field: String): Long {
            if (remaining < 4) fail("truncated $field")
            var v = 0L
            repeat(4) { v = v or ((data[pos + it].toLong() and 0xff) shl (8 * it)) }
            pos += 4
            return v
        }

        fun raw(n: Int, field: String): ByteArray {
            if (remaining < n) fail("truncated $field ($n bytes short)")
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun cString(field: String): String {
            var end = -1
            for (i in pos until data.size) {
                if (data[i].toInt() == 0) {
                    end = i
                    break
                }
            }
            if (end < 0) fail("unterminated $field C-string")
            val text = data.copyOfRange(pos, end).toString(Charsets.US_ASCII)
            pos = end + 1
            return text
        }

        /**
         * The C-string's RAW bytes (without the NUL), advanced past — the
         * rewrite sink's verbatim copy path (code-red R2-5: re-encoding a
         * String through US-ASCII mapped every byte >= 0x80 to '?' and
         * corrupted non-ASCII realm names; the wire bytes are copied, not
         * transcoded). */
        fun cStringBytes(field: String): ByteArray {
            var end = -1
            for (i in pos until data.size) {
                if (data[i].toInt() == 0) {
                    end = i
                    break
                }
            }
            if (end < 0) fail("unterminated $field C-string")
            val bytes = data.copyOfRange(pos, end)
            pos = end + 1
            return bytes
        }
    }

    /** Little-endian writer; supports reserving the u16 size slot. */
    private class Writer {
        private val out = ArrayList<Byte>(256)
        private var sizeSlot: Int = -1

        val size: Int get() = out.size

        fun reserveSizeField() {
            sizeSlot = out.size
            out.add(0)
            out.add(0)
        }

        fun fillReservedSizeField(payloadSize: Int) {
            check(sizeSlot >= 0) { "no reserved size slot" }
            // The u16 size field is the frame's ONLY length declaration —
            // a rebuilt payload beyond it would MASK (code-red R2-2: a
            // legal 65529-byte input grew past 0xFFFF through the
            // replacement address and declared size 4) and silently
            // desync the client's frame parser. Refuse instead.
            if (payloadSize > U16_MAX) {
                fail(
                    "the rebuilt payload ($payloadSize bytes) exceeds the " +
                        "u16 size field — the rewrite would corrupt the " +
                        "stream framing",
                )
            }
            out[sizeSlot] = (payloadSize and 0xff).toByte()
            out[sizeSlot + 1] = ((payloadSize ushr 8) and 0xff).toByte()
        }

        fun rawCString(bytes: ByteArray) {
            raw(bytes)
            u8(0)
        }

        fun u8(v: Int) {
            out.add((v and 0xff).toByte())
        }

        fun u16(v: Int) {
            out.add((v and 0xff).toByte())
            out.add(((v ushr 8) and 0xff).toByte())
        }

        fun u32(v: Long) {
            repeat(4) { i -> out.add(((v ushr (8 * i)) and 0xffL).toByte()) }
        }

        fun cString(s: String) {
            for (b in s.toByteArray(Charsets.US_ASCII)) out.add(b)
            out.add(0)
        }

        fun raw(bytes: ByteArray) {
            for (b in bytes) out.add(b)
        }

        fun toByteArray(): ByteArray = ByteArray(out.size) { out[it] }
    }
}

/** The one loud refusal for every layout violation (file-level so the
 * object's function count stays within the review bound). */
private fun fail(reason: String): Nothing =
    throw RealmListRewrite.RealmListRewriteException("VAL-IROH-REALMLIST: $reason")

/**
 * The address C-string must be "host:port" with a 1..5-digit port — the
 * shape realmd itself produces (RealmList.cpp:155-158 appends ":port").
 * File-level so the object's function count stays within the review bound.
 * The HOST part is validated only for non-emptiness and printability: the
 * joiner accepts whatever the host advertised (LAN IP, tunnel CGNAT
 * literal, numeric public IP — the value's flavor is the host's business;
 * the JOINER never dials it, only replaces it). the refusal names
 * the failed SHAPE, never the peer-chosen VALUE.
 */
private fun validateAddressShapeLadder(address: String, fail: (String) -> Nothing) {
    if (address.isEmpty()) fail("address C-string is empty")
    if (address.length > 255) fail("address C-string exceeds 255 bytes")
    val colon = address.lastIndexOf(':')
    if (colon <= 0 || colon == address.length - 1) {
        fail("address does not carry a :port suffix")
    }
    val port = address.substring(colon + 1)
    if (port.length > 5 || port.any { !it.isDigit() }) {
        fail("address port is not a 1..5-digit number")
    }
    val host = address.substring(0, colon)
    if (host.any { it.code < 0x20 || it.code > 0x7e }) {
        fail("address host carries non-printable bytes")
    }
}
