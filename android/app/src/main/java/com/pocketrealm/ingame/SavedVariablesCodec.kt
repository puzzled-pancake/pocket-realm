package com.pocketrealm.ingame

/**
 * Strict scalar codec for build 5875's account-level
 * `WTF/Account/<name>/SavedVariables.lua` (the on-disk form pinned by
 * docs/INGAME_SETTINGS_GROUND_TRUTH.md: assignments of strings, bare
 * numbers, booleans, and nil, separated by \r\r\n terminators).
 *
 * Only top-level scalar assignments are understood; every other line
 * (tables, comments, addon-written globals, blank separators) round-trips
 * verbatim. A variable that is present but non-scalar is refused, never
 * structurally edited.
 */
object SavedVariablesCodec {

    sealed interface Value {
        data class Str(val raw: String) : Value
        data class Num(val raw: String) : Value
        data class Bool(val raw: Boolean) : Value
        data object Nil : Value
    }

    private val scalarLine = Regex(
        """^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(nil|true|false|-?\d+(?:\.\d+)?|"(?:[^"\\\r\n]|\\.)*")\s*$""",
    )

    private val anyAssignment = Regex("""^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=""")

    /** True when [name] is assigned anything at all (scalar or structural). */
    fun isAssigned(text: String, name: String): Boolean =
        contentLines(text).any { line ->
            anyAssignment.find(line.body.trim())?.groupValues?.get(1) == name
        }

    /** Scalar assignments in encounter order, last-wins; other lines are omitted. */
    fun parse(text: String): Map<String, Value> {
        val values = linkedMapOf<String, Value>()
        contentLines(text).forEach { line ->
            val match = scalarLine.matchEntire(line.body.trim()) ?: return@forEach
            values[match.groupValues[1]] = literal(match.groupValues[2])
        }
        return values
    }

    /** True when [name] is present as a scalar (the only editable case). */
    fun isEditableScalar(text: String, name: String): Boolean =
        parse(text).containsKey(name)

    /**
     * Replace or append `name = value`; [value] == null removes every
     * assignment of [name]. An existing scalar keeps its own literal form
     * (string stays quoted, number stays bare); a new assignment uses the
     * catalog's declared form ([numberForm] renders bare). Returns null when
     * [name] appears only as a non-scalar line — the caller surfaces a
     * "not editable outside the game" state instead of editing structure.
     */
    fun assign(text: String, name: String, value: String?, numberForm: Boolean = false): String? {
        val lines = contentLines(text).toMutableList()
        var replaced = false
        var seenTarget = false
        val writer = mutableListOf<String>()
        lines.forEach { line ->
            val match = scalarLine.matchEntire(line.body.trim())
            if (match == null || match.groupValues[1] != name) {
                writer += line.original
                return@forEach
            }
            seenTarget = true
            if (value != null && !replaced) {
                val keepsBareForm = literal(match.groupValues[2]) !is Value.Str
                val body = if (keepsBareForm) value else "\"$value\""
                writer += "$name = $body${line.terminator}"
                replaced = true
            }
            // value == null, or a duplicate assignment: the line is dropped.
        }
        if (replaced || value == null) {
            if (seenTarget) return writer.joinToString("")
            // Removal of an absent name: nothing to do, byte-identical result.
            return text
        }
        if (seenTarget) return null
        // A structural assignment of the same name must never gain a scalar twin.
        if (isAssigned(text, name)) return null
        val ending = detectTerminator(text)
        val separator = if (text.isEmpty() || text.endsWith("\n") || text.endsWith("\r")) "" else ending
        return writer.joinToString("") + separator +
            "$name = ${if (numberForm) value else "\"$value\""}$ending"
    }

    /** The dominant terminator the file already uses, defaulting to \r\r\n. */
    fun detectTerminator(text: String): String = when {
        text.contains("\r\r\n") -> "\r\r\n"
        text.contains("\r\n") -> "\r\n"
        text.contains("\n") -> "\n"
        else -> "\r\r\n"
    }

    private class ContentLine(val body: String, val terminator: String) {
        val original: String get() = body + terminator
    }

    // Split into (content, terminator) pairs, tolerating every terminator the
    // client or host writers produce. A trailing chunk without a terminator
    // (last line, no final newline) still yields its own ContentLine.
    private fun contentLines(text: String): List<ContentLine> {
        val lines = mutableListOf<ContentLine>()
        var index = 0
        while (index < text.length) {
            val remaining = text.substring(index)
            val cut = terminatorAt(remaining)
            if (cut == null) {
                lines += ContentLine(remaining, "")
                break
            }
            lines += ContentLine(remaining.substring(0, cut.first), cut.second)
            index += cut.first + cut.second.length
        }
        return lines
    }

    private fun terminatorAt(text: String): Pair<Int, String>? {
        val candidates = listOf("\r\r\n", "\r\n", "\n", "\r")
        var best: Pair<Int, String>? = null
        candidates.forEach { candidate ->
            val at = text.indexOf(candidate)
            if (at >= 0 && (best == null || at < best!!.first)) {
                best = at to candidate
            }
        }
        // A "\r\r\n" must win over its "\r\n"/"\r" substrings at the same spot.
        return best
    }

    private fun literal(literal: String): Value = when (literal) {
        "nil" -> Value.Nil
        "true" -> Value.Bool(true)
        "false" -> Value.Bool(false)
        else -> if (literal.startsWith("\"")) {
            Value.Str(
                literal.removePrefix("\"").removeSuffix("\"")
                    .replace("\\\"", "\"").replace("\\\\", "\\"),
            )
        } else {
            Value.Num(literal)
        }
    }
}
