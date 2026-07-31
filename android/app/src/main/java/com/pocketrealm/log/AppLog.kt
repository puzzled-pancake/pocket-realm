package com.pocketrealm.log

import android.util.Log
import org.json.JSONObject

/**
 * Structured logging facade. Emits logcat AND keeps a bounded in-memory ring for
 * the Diagnostics screen. Every entry is a typed event with a stable `kind`
 * string so logs are greppable and the support bundle (O21) can redact them.
 *
 * No passwords, credentials, or realm secrets are ever logged (DECISIONS #28).
 */
object AppLog {

    private const val MAX_ENTRIES = 800
    private val ring = ArrayDeque<Line>()
    private val lock = Any()

    enum class Level(val priority: Int) { DEBUG(Log.DEBUG), INFO(Log.INFO), WARN(Log.WARN), ERROR(Log.ERROR) }

    data class Line(val ts: Long, val level: Level, val kind: String, val message: String)

    fun d(kind: String, msg: String) = emit(Level.DEBUG, kind, msg)
    fun i(kind: String, msg: String) = emit(Level.INFO, kind, msg)
    fun w(kind: String, msg: String) = emit(Level.WARN, kind, msg)
    fun e(kind: String, msg: String, t: Throwable? = null) = emit(Level.ERROR, kind, msg, t)

    fun snapshot(): List<Line> = synchronized(lock) { ring.toList() }

    fun exportJson(): String {
        val arr = synchronized(lock) { ring.toList() }
        return arr.joinToString(prefix = "[", postfix = "]") { l ->
            JSONObject()
                .put("ts", l.ts)
                .put("level", l.level.name)
                .put("kind", l.kind)
                .put("msg", l.message)
                .toString()
        }
    }

    private fun emit(level: Level, kind: String, msg: String, t: Throwable? = null) {
        val line = Line(System.currentTimeMillis(), level, kind, msg)
        synchronized(lock) {
            if (ring.size >= MAX_ENTRIES) ring.removeFirst()
            ring.addLast(line)
        }
        val tag = "PR/$kind"
        if (t != null) Log.println(level.priority, tag, msg + "\n" + Log.getStackTraceString(t))
        else Log.println(level.priority, tag, msg)
    }
}
