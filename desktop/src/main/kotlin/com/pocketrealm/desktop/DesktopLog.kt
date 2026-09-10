package com.pocketrealm.desktop

import java.io.File
import java.io.FileOutputStream
import java.io.PrintStream
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Desktop log sink: console + a file under the storage roots. The shared
 * native realm runtime delivers its log lines through the JNI shims with an
 * explicit (level, message) pair; this sink is where they land on Windows.
 * Thread-safe and non-blocking per the ABI contract (synchronized append).
 */
object DesktopLog {
    private val FORMAT = DateTimeFormatter.ISO_INSTANT

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
    }

    fun i(tag: String, message: String) = log("INFO", tag, message)

    fun w(tag: String, message: String) = log("WARN", tag, message)

    fun e(tag: String, message: String) = log("ERROR", tag, message)
}
