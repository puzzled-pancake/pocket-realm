package com.pocketrealm.discovery.nostr

/**
 * Minimal JSON wire helpers: NIP-01's "encode minimally, UTF-8, no
 * whitespace" string escaper and the pinned-order serializers. The
 * escaper passes non-ASCII through (real UTF-8) and escapes only what
 * JSON requires — matching the reference implementations' behavior so a
 * foreign client re-serializes to the same bytes.
 *
 * W6-N3: extracted verbatim from RealmListingEvent.kt (same package, zero
 * behavior change) so the discovery/iroh shared core (the join descriptor's
 * pinned-order serializer) can share THE escaper instead of growing a twin
 * — one escaping implementation per wire format, byte-stable goldens on
 * both lanes plus the gateway.
 */
object NostrJson {

    // \u00XX control escapes: hex digits are locale-neutral by definition.
    private const val HEX_RADIX = 16
    private const val HEX_DIGITS = 4

    fun quote(raw: String): String = buildString {
        append('"')
        for (ch in raw) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\b' -> append("\\b")
                ch == '\u000C' -> append("\\f")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append(buildControlEscape(ch))
                else -> append(ch)
            }
        }
        append('"')
    }

    /** The C0 control-character escape (`\b \t \n \f \r`, else \u00XX):
     * the only remaining quote() branch, kept out of the when so every
     * line stays inside the length gate. */
    private fun buildControlEscape(ch: Char): String =
        when (ch) {
            '\b' -> "\\b"
            '\u000C' -> "\\f"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> "\\u" + ch.code.toString(HEX_RADIX).padStart(HEX_DIGITS, '0')
        }

    /** A Double as a JSON number or null — NaN/Infinity are refused (they
     * are not JSON). */
    fun numberOrNull(value: Double?): String = when {
        value == null -> "null"
        value.isNaN() || value.isInfinite() ->
            throw IllegalArgumentException("VAL-NOSTR-CONTENT: rates must be finite numbers")
        else -> value.toString()
    }

    /** Tags as a JSON array-of-arrays, order preserved. */
    fun tagsToJson(tags: List<List<String>>): String = buildString {
        append('[')
        tags.forEachIndexed { i, tag ->
            if (i > 0) append(',')
            append('[')
            tag.forEachIndexed { j, value ->
                if (j > 0) append(',')
                append(quote(value))
            }
            append(']')
        }
        append(']')
    }
}
