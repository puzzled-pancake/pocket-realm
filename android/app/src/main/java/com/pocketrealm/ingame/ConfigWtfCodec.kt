package com.pocketrealm.ingame

/**
 * Line-oriented codec for `WTF/Config.wtf` under the merge model
 * :
 *
 *  - the base file is the authority for everything the app does not own;
 *    unknown lines round-trip verbatim in content and relative order;
 *  - app-enforced keys are *written or deleted* — an enforced key whose
 *    condition is false this launch is removed, so stale lines cannot
 *    survive audio on→off, loopback→LAN, or renderer flips;
 *  - queued user overrides apply last but never to an enforced key;
 *  - duplicate keys resolve last-wins at parse time (the surviving line
 *    keeps the first occurrence's position);
 *  - output is serialized with CRLF terminators.
 */
object ConfigWtfCodec {

    private val setLine = Regex("""^SET\s+([A-Za-z0-9_]+)\s+"([^"\r\n]*)"\s*$""")
    private val tolerantSetLine = Regex(
        """^SET\s+([A-Za-z0-9_]+)(?:\s+(?:"([^"\r\n]*)"|([^\s"][^\r\n]*)))?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** One enforced overlay entry; [value] == null deletes the key this launch. */
    data class EnforcedLine(val key: String, val value: String?)

    /** A queued user override; [value] == null removes the key's line. */
    data class UserOverride(val key: String, val value: String?)

    data class MergeResult(val text: String, val skippedOverrides: List<String>)

    private class Line private constructor(
        val key: String?,
        val value: String?,
        val raw: String?,
        val deleted: Boolean,
    ) {
        constructor(key: String, value: String) : this(key, value, null, false)
        constructor(raw: String) : this(null, null, raw, false)
        constructor() : this(null, null, null, true)

        fun withValue(next: String): Line = Line(key!!, next)

        fun rendered(): String = when {
            raw != null -> raw
            key != null -> "SET $key \"$value\""
            else -> ""
        }
    }

    fun parse(text: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        splitLines(text).forEach { line ->
            parseLine(line)?.let { values[it.first] = it.second }
        }
        return values
    }

    fun valueForKey(text: String, key: String): String? = parse(text)[key]

    /**
     * Merge enforced lines and user overrides over [base]. Enforced keys are
     * replaced in place when present and appended in order when absent;
     * enforced keys with a null value are deleted. User overrides targeting
     * an enforced key are skipped (reported by key) so the caller can retain
     * them undelivered.
     */
    fun merge(
        base: String?,
        enforced: List<EnforcedLine>,
        overrides: List<UserOverride> = emptyList(),
    ): MergeResult {
        val lines = mutableListOf<Line>()
        val index = linkedMapOf<String, Int>()
        splitLines(base.orEmpty()).forEach { raw ->
            val parsed = parseLine(raw) ?: run { lines += verbatim(raw); return@forEach }
            val (key, value) = parsed
            val existing = index[key]
            if (existing == null) {
                index[key] = lines.size
                lines += Line(key, value)
            } else {
                lines[existing] = Line(key, value)
            }
        }
        val enforcedKeys = enforced.map { it.key }.toHashSet()
        enforced.forEach { entry ->
            val position = index[entry.key]
            when {
                entry.value == null -> position?.let { lines[it] = Line() }
                position != null -> lines[position] = lines[position].withValue(entry.value)
                else -> {
                    index[entry.key] = lines.size
                    lines += Line(entry.key, entry.value)
                }
            }
        }
        val skipped = mutableListOf<String>()
        overrides.forEach { override ->
            if (override.key in enforcedKeys) {
                skipped += override.key
                return@forEach
            }
            val position = index[override.key]
            when {
                override.value == null -> position?.let { lines[it] = Line() }
                position != null -> lines[position] = lines[position].withValue(override.value)
                else -> {
                    index[override.key] = lines.size
                    lines += Line(override.key, override.value)
                }
            }
        }
        val body = lines.filterNot { it.deleted }.joinToString("\r\n") { it.rendered() }
        val text = if (body.isEmpty()) "" else "$body\r\n"
        return MergeResult(text, skipped)
    }

    /** Renders a float the way build 5875 echoes CVar values (six decimals,
     *  always dot-separated regardless of the device locale). */
    fun formatValue(value: Float): String = String.format(java.util.Locale.US, "%.6f", value)

    // A non-SET line still occupies a slot so ordering is preserved, but it
    // never takes a key and round-trips verbatim.
    private fun verbatim(raw: String): Line = Line(raw)

    // Split on any terminator the client or host writers produce
    // (\r\r\n, \r\n, \n, lone \r), keeping line content clean of them.
    private fun splitLines(text: String): List<String> =
        text.split(Regex("\r\r\n|\r\n|\n|\r")).filter { it.isNotEmpty() }

    private fun parseLine(raw: String): Pair<String, String>? {
        val trimmed = raw.trim()
        setLine.matchEntire(trimmed)?.let { match ->
            return match.groupValues[1] to match.groupValues[2]
        }
        tolerantSetLine.matchEntire(trimmed)?.let { match ->
            val value = match.groupValues[2].ifEmpty { match.groupValues[3] }
            return match.groupValues[1] to value
        }
        return null
    }
}
