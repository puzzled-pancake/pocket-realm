package com.pocketrealm.desktop

import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.json.JSONObject

/**
 * Desktop log sink: console + a file under the storage roots. The shared
 * native realm runtime delivers its log lines through the JNI shims with an
 * explicit (level, message) pair; this sink is where they land on Windows.
 * Thread-safe and non-blocking per the ABI contract (synchronized append).
 *
 * DESK-110: every line is also a typed event in a bounded in-memory ring
 * (the Android AppLog contract: 800 entries, stable `kind`, snapshot()/
 * exportJson()) so the Diagnostics screen and the support bundle's
 * logs.json survive even when the file write failed, and logs stay
 * greppable by kind. The file append is unchanged.
 */
object DesktopLog {
    private val FORMAT = DateTimeFormatter.ISO_INSTANT

    /** One typed log event (the shared AppLog.Line contract; the desktop's
     * level is the existing INFO/WARN/ERROR string). */
    data class Line(val ts: Long, val level: String, val kind: String, val message: String)

    private const val MAX_ENTRIES = 800
    private val ring = ArrayDeque<Line>()
    private val ringLock = Any()

    @Volatile
    private var sink: PrintStream? = null

    fun attachFile(logsDir: File) {
        logsDir.mkdirs()
        val file = File(logsDir, "pocket-realm.log")
        val replacement = PrintStream(FileOutputStream(file, true), false, Charsets.UTF_8)
        // Close the previous sink on re-attach instead of leaking its
        // file handle (each bring-up main attaches once; tests may attach
        // repeatedly against fresh roots).
        sink?.close()
        sink = replacement
    }

    fun log(level: String, tag: String, message: String) {
        val line = "${FORMAT.format(Instant.now())} $level/$tag: $message"
        println(line)
        sink?.let { stream ->
            synchronized(stream) {
                stream.println(line)
                stream.flush()
            }
        }
        synchronized(ringLock) {
            if (ring.size >= MAX_ENTRIES) ring.removeFirst()
            ring.addLast(Line(System.currentTimeMillis(), level, tag, message))
        }
    }

    /** A copy of the ring (the Android AppLog.snapshot contract). */
    fun snapshot(): List<Line> = synchronized(ringLock) { ring.toList() }

    /** The ring as the support bundle's logs.json body (the AppLog shape:
     * one {"ts","level","kind","msg"} object per entry). */
    fun exportJson(): String {
        val lines = synchronized(ringLock) { ring.toList() }
        return lines.joinToString(prefix = "[", postfix = "]") { line ->
            JSONObject()
                .put("ts", line.ts)
                .put("level", line.level)
                .put("kind", line.kind)
                .put("msg", line.message)
                .toString()
        }
    }

    fun i(tag: String, message: String) = log("INFO", tag, message)

    fun w(tag: String, message: String) = log("WARN", tag, message)

    fun e(tag: String, message: String) = log("ERROR", tag, message)
}
