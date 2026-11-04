package com.pocketrealm.storage

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * The accounts this device made on other people's games (the first-join
 * wizard's store): one {name, password} per game, keyed by the game's
 * identity string. The next join of a game with a saved login skips the
 * wizard and connects straight away.
 *
 * Stored with the same discipline as the single-player account store: one
 * owner-only file, written atomically, never in logs, journals or support
 * bundles; the default toString of the record never shows the password.
 * A game the old sign-up set knew but whose password was never saved
 * migrates as an entry with no credentials ("has an account here").
 */
class JoinedRealmLogins(private val file: File) {

    data class SavedLogin(
        /** The account name, or null for a migrated "has an account here" entry. */
        val username: String?,
        val password: String?,
    ) {
        val complete: Boolean get() = !username.isNullOrEmpty() && !password.isNullOrEmpty()
        override fun toString(): String =
            if (complete) "saved login" else "account made here (no saved login)"
    }

    fun savedLogin(realmKey: String): SavedLogin? = synchronized(lock) {
        val raw = read().optJSONObject(escape(realmKey)) ?: return null
        SavedLogin(
            username = raw.optString(NAME).takeIf { it.isNotEmpty() },
            password = raw.optString(PASSWORD).takeIf { it.isNotEmpty() },
        )
    }

    fun save(realmKey: String, username: String, password: String) = synchronized(lock) {
        require(username.isNotEmpty() && password.isNotEmpty()) {
            "a saved login needs both the name and the password"
        }
        val all = read()
        all.put(
            escape(realmKey),
            JSONObject().put(NAME, username).put(PASSWORD, password),
        )
        write(all)
    }

    /** The migrated entry: an account exists on that game, no saved password. */
    fun markAccountMade(realmKey: String) = synchronized(lock) {
        val key = escape(realmKey)
        val all = read()
        if (all.optJSONObject(key) == null) {
            all.put(key, JSONObject().put(NAME, "").put(PASSWORD, ""))
            write(all)
        }
    }

    fun forget(realmKey: String) = synchronized(lock) {
        val all = read()
        all.remove(escape(realmKey))
        write(all)
    }

    fun keys(): Set<String> = synchronized(lock) {
        read().keys().asSequence().map(::unescape).toSet()
    }

    // ------------------------------------------------------------- file body

    private val lock = Any()

    private fun read(): JSONObject = runCatching {
        if (file.isFile) JSONObject(file.readText(Charsets.UTF_8)) else JSONObject()
    }.getOrNull() ?: JSONObject()

    private fun write(all: JSONObject) {
        file.parentFile?.mkdirs()
        restrictToOwner(file.parentFile)
        val temp = File(file.parentFile, ".${file.name}.tmp")
        temp.writeText(all.toString(), Charsets.UTF_8)
        restrictToOwner(temp)
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "could not write ${file.name}" }
        }
    }

    /** Owner-only where the platform supports it; a quiet no-op elsewhere. */
    private fun restrictToOwner(target: File?) {
        if (target == null) return
        runCatching {
            val path = target.toPath()
            if (target.isDirectory) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
            } else {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            }
        }
    }

    /** Game keys are hex ids or "lan:<address>" — escape the dots either way. */
    private fun escape(key: String): String = key.replace(':', ';')
    private fun unescape(key: String): String = key.replace(';', ':')

    private companion object {
        const val NAME = "name"
        const val PASSWORD = "password"
    }
}
