package com.pocketrealm.client

internal data class WineRunResult(
    val rc: Int,
    val exitStatus: Int,
    val timedOut: Boolean,
    val processTreeDrained: Boolean,
    val stdout: String,
    val stderr: String,
) {
    val exitedCleanly: Boolean get() = exitStatus >= 0 && exitStatus and 0x7f == 0
    val exitCode: Int get() = if (exitedCleanly) (exitStatus shr 8) and 0xff else -1
}

internal fun parseWineRunResult(value: String): WineRunResult {
    val stdoutMarker = "\n@@@STDOUT@@@\n"
    val stderrMarker = "\n@@@STDERR@@@\n"
    val stdoutAt = value.indexOf(stdoutMarker)
    val stderrAt = value.indexOf(stderrMarker)
    val header = value.lineSequence().firstOrNull().orEmpty()
    fun field(name: String): Int {
        val token = "$name="
        val start = header.indexOf(token)
        if (start < 0) return -1
        val valueStart = start + token.length
        val end = header.indexOf('|', valueStart).let { if (it < 0) header.length else it }
        return header.substring(valueStart, end).toIntOrNull() ?: -1
    }
    val stdout = if (stdoutAt >= 0 && stderrAt > stdoutAt) {
        value.substring(stdoutAt + stdoutMarker.length, stderrAt)
    } else ""
    val stderr = if (stderrAt >= 0) value.substring(stderrAt + stderrMarker.length) else ""
    return WineRunResult(
        field("RC"), field("EXIT"), field("TIMED_OUT") == 1,
        field("TREE_DRAINED") == 1, stdout, stderr,
    )
}
