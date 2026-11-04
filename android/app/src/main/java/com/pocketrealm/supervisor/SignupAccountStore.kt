package com.pocketrealm.supervisor

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The accounts strangers created through self sign-up on this realm (the
 * host's "signed-up accounts" list and the sign-up cap's count). One JSON
 * file under the realm data root, written atomically; a corrupt file reads
 * as empty rather than blocking the host. Holds names and creation times
 * only — never a verifier or anything derived from a password.
 */
class SignupAccountStore(private val file: File) {

    data class Entry(val username: String, val createdAtMs: Long)

    private val lock = Any()

    fun list(): List<Entry> = synchronized(lock) { read() }

    fun names(): List<String> = list().map { it.username }

    /** Records a created account (idempotent on the name, case-blind). */
    fun add(username: String, createdAtMs: Long) = synchronized(lock) {
        val current = read()
        if (current.any { it.username.equals(username, ignoreCase = true) }) return@synchronized
        write(current + Entry(username, createdAtMs))
    }

    /** Drops an account from the list; true when it was listed. */
    fun remove(username: String): Boolean = synchronized(lock) {
        val current = read()
        val kept = current.filterNot { it.username.equals(username, ignoreCase = true) }
        if (kept.size == current.size) return@synchronized false
        write(kept)
        true
    }

    private fun read(): List<Entry> = runCatching {
        if (!file.isFile) return emptyList()
        val obj = JSONObject(file.readText(Charsets.UTF_8))
        if (obj.optInt("schema") != SCHEMA) return emptyList()
        val rows = obj.optJSONArray("accounts") ?: return emptyList()
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val name = row.optString("username")
            if (name.isEmpty()) null else Entry(name, row.optLong("createdAtMs"))
        }
    }.getOrDefault(emptyList())

    private fun write(entries: List<Entry>) {
        file.parentFile?.mkdirs()
        val rows = JSONArray()
        entries.forEach { rows.put(JSONObject().put("username", it.username).put("createdAtMs", it.createdAtMs)) }
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(JSONObject().put("schema", SCHEMA).put("accounts", rows).toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "could not write ${file.name}" }
        }
    }

    companion object {
        const val SCHEMA: Int = 1
        const val FILE_NAME: String = "signup-accounts.json"
    }
}
