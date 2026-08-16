package com.pocketrealm.ingame

/**
 * Line codec for `bindings-cache.wtf`, sharing the grammar proven by
 * `AndroidPortBindingRepair` / `LegacyControllerBindingRepair`:
 * quote-optional on parse (`bind KEY COMMAND` or `bind "KEY" "COMMAND"`),
 * unquoted on emission, with SHIFT-/CTRL-/CTRL-SHIFT-/ALT- chords,
 * MOUSEWHEEL/BUTTON tokens, and any of the \r\r\n/\r\n/\n/\r terminators
 * observed on device. Non-bind lines round-trip verbatim.
 */
object BindingsFileCodec {

    private val bindLine = Regex(
        """^\s*bind\s+"?([^"\s]+)"?\s+"?([^"\r\n]+?)"?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Commands bound to a key, in file order (normally exactly one). */
    fun parse(text: String): Map<String, String> {
        val bindings = linkedMapOf<String, String>()
        contentLines(text).forEach { line ->
            val match = bindLine.matchEntire(line.body) ?: return@forEach
            bindings[match.groupValues[1].uppercase()] = match.groupValues[2].uppercase()
        }
        return bindings
    }

    /** Keys bound to [command] in file order — slot 1 is the primary. */
    fun keysForCommand(text: String, command: String): List<String> =
        parse(text).filterValues { it == command.uppercase() }.keys.toList()

    /**
     * Replace every binding of [key]. [command] == null unbinds the key.
     * The edited line is emitted unquoted at the key's existing position, or
     * appended after the last bind line (deterministic placement for fresh
     * entries). All other lines round-trip verbatim.
     */
    fun assign(text: String, key: String, command: String?): String {
        val normalizedKey = key.uppercase()
        val lines = contentLines(text).toMutableList()
        var replaced = false
        var lastBindIndex = -1
        lines.forEachIndexed { index, line ->
            val match = bindLine.find(line.body) ?: return@forEachIndexed
            lastBindIndex = index
            if (match.groupValues[1].uppercase() != normalizedKey) return@forEachIndexed
            if (command != null && !replaced) {
                lines[index] = ContentLine("bind $normalizedKey ${command.uppercase()}", line.terminator)
                replaced = true
            } else {
                // Unbind, or a duplicate claim of the key: drop the line.
                lines[index] = ContentLine("", line.terminator, deleted = true)
            }
        }
        if (command != null && !replaced) {
            val entry = ContentLine(
                "bind $normalizedKey ${command.uppercase()}",
                detectTerminator(text),
            )
            if (lastBindIndex >= 0) lines.add(lastBindIndex + 1, entry) else lines.add(entry)
        }
        return lines.filterNot { it.deleted }.joinToString("") { it.original }
    }

    /** The dominant terminator the file already uses, defaulting to CRLF. */
    fun detectTerminator(text: String): String = when {
        text.contains("\r\r\n") -> "\r\r\n"
        text.contains("\r\n") -> "\r\n"
        text.contains("\n") -> "\n"
        else -> "\r\n"
    }

    private class ContentLine(val body: String, val terminator: String, val deleted: Boolean = false) {
        val original: String get() = body + terminator
    }

    private fun contentLines(text: String): List<ContentLine> {
        val lines = mutableListOf<ContentLine>()
        var index = 0
        while (index < text.length) {
            val remaining = text.substring(index)
            val cut = terminatorAt(remaining) ?: run {
                lines += ContentLine(remaining, "")
                return lines
            }
            lines += ContentLine(remaining.substring(0, cut.first), cut.second)
            index += cut.first + cut.second.length
        }
        return lines
    }

    private fun terminatorAt(text: String): Pair<Int, String>? {
        var best: Pair<Int, String>? = null
        listOf("\r\r\n", "\r\n", "\n", "\r").forEach { candidate ->
            val at = text.indexOf(candidate)
            if (at >= 0 && (best == null || at < best!!.first)) best = at to candidate
        }
        return best
    }
}
