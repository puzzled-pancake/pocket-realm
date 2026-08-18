package com.pocketrealm.database

internal data class DatabaseRunResult(
    val launcherRc: Int,
    val waitStatus: Int,
    val timedOut: Boolean,
    val stdout: String,
    val stderr: String,
) {
    val exited: Boolean get() = waitStatus >= 0 && waitStatus and 0x7f == 0
    val exitCode: Int get() = if (exited) (waitStatus shr 8) and 0xff else -1
    val signal: Int get() = if (waitStatus >= 0 && !exited) waitStatus and 0x7f else 0
    val ok: Boolean get() = launcherRc == 0 && exited && exitCode == 0 && !timedOut

    companion object {
        fun parse(raw: String): DatabaseRunResult {
            val stdoutMarker = "\n@@@STDOUT@@@\n"
            val stderrMarker = "\n@@@STDERR@@@\n"
            val stdoutAt = raw.indexOf(stdoutMarker)
            val stderrAt = raw.indexOf(stderrMarker)
            require(stdoutAt > 0 && stderrAt > stdoutAt) { "malformed native result" }
            val fields = raw.substring(0, stdoutAt).lineSequence().first().split('|')
                .associate { field -> field.substringBefore('=') to field.substringAfter('=') }
            return DatabaseRunResult(
                launcherRc = fields.getValue("RC").toInt(),
                waitStatus = fields.getValue("EXIT").toInt(),
                timedOut = fields.getValue("TIMED_OUT") == "1",
                stdout = raw.substring(stdoutAt + stdoutMarker.length, stderrAt),
                stderr = raw.substring(stderrAt + stderrMarker.length),
            )
        }
    }
}
