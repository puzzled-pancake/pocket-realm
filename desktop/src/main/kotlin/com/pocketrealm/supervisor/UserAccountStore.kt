package com.pocketrealm.supervisor

import com.pocketrealm.client.ClientFlavors
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Desktop twin of the Android UserAccountStore (same package + name so the
 * shared sources compile unmodified). Stores the account the user typed on
 * the Home screen so the client launch can auto-login as that identity.
 *
 * Same contract as the Android store — atomic write, corrupt-record
 * quarantine, 1..16 ASCII-alphanumeric validation, secret never in
 * toString — over %LOCALAPPDATA%\PocketRealm\user-account\account.json.
 * The Android store's POSIX owner-only chmod has no leg here: the record
 * lives under the user profile directory, private by NTFS profile ACLs
 * (the same posture as the desktop ServerRuntimeFiles).
 */
@Suppress("MagicNumber") // schema/id/gm bands mirror the Android twin exactly
class UserAccountStore(private val directory: File) {
    private val lock = Any()
    private val recordFile = File(directory, RECORD)

    fun save(
        username: String,
        password: String,
        accountId: Long,
        gmLevel: Int = 0,
        flavor: String = ClientFlavors.VANILLA.flavorId,
    ): UserAccount = synchronized(lock) {
        require(isValidCredential(username)) { "invalid user-account username" }
        require(isValidCredential(password)) { "invalid user-account password" }
        require(accountId > 0) { "user-account id is invalid" }
        require(gmLevel in 0..3) { "user-account privilege is invalid" }
        require(flavor in ClientFlavors.ALL.map { it.flavorId }) { "invalid user-account flavor" }
        val account = UserAccount(username, password, accountId, gmLevel, flavor)
        writeLocked(account)
        account
    }

    fun loadProvisioned(): UserAccount? = synchronized(lock) { readLocked() }

    fun loadOrQuarantine(): UserAccount? = synchronized(lock) {
        runCatching { readLocked() }.getOrElse {
            quarantineLocked()
            null
        }
    }

    /**
     * The user's explicit removal: the active record AND every forensic
     * quarantine copy go — each `account.invalid.*.json` carries the full
     * plaintext username+password record, so a removal that left them
     * behind would keep the secret on disk after the user asked for it to
     * be gone. (The quarantine copies exist for UNINTENTIONAL transitions
     * — a corrupt load — where triage needs the bytes; an
     * intentional clear revokes that retention.)
     */
    fun clear(): Unit = synchronized(lock) {
        if (recordFile.isFile) check(recordFile.delete()) { "stored user account could not be removed" }
        directory.listFiles { file -> file.name.startsWith("account.invalid.") }
            ?.forEach { file -> runCatching { file.delete() } }
    }

    private fun readLocked(): UserAccount? {
        if (!recordFile.isFile) return null
        return decodeRecord(JSONObject(recordFile.readText(Charsets.UTF_8)))
    }

    private fun writeLocked(account: UserAccount) {
        directory.mkdirs()
        val temp = File(directory, ".user-account.${ProcessHandle.current().pid()}.tmp")
        val encoded = encodeRecord(account)
        FileOutputStream(temp).use { stream ->
            stream.write(encoded)
            stream.fd.sync()
        }
        // ATOMIC_MOVE first with the plain-replace fallback its sibling
        // stores carry: AppData can be redirected (roaming profiles) onto
        // filesystems that reject the atomic flag, and a save must
        // degrade, not fail outright.
        try {
            java.nio.file.Files.move(
                temp.toPath(), recordFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(
                temp.toPath(), recordFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun quarantineLocked() {
        if (!recordFile.isFile) return
        directory.mkdirs()
        // nanoTime suffix: two corrupt loads within one millisecond must
        // not silently overwrite each other's forensic copy (the Android
        // twin's Os.rename fails loudly; Files.move would replace).
        val quarantine = File(
            directory,
            "account.invalid.${System.currentTimeMillis()}-${System.nanoTime()}.json",
        )
        runCatching {
            java.nio.file.Files.move(
                recordFile.toPath(), quarantine.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }
        directory.listFiles { file -> file.name.startsWith("account.invalid.") }
            ?.sortedByDescending(File::lastModified)
            ?.drop(2)
            ?.forEach(File::delete)
    }

    /** Deliberately not a data class: default toString must never reveal the secret. */
    class UserAccount internal constructor(
        val username: String,
        val password: String,
        val accountId: Long,
        val gmLevel: Int = 0,
        val flavor: String = ClientFlavors.VANILLA.flavorId,
    ) {
        override fun toString(): String =
            "UserAccount(username=<redacted>, password=<redacted>, accountId=$accountId, gmLevel=$gmLevel)"
    }

    companion object {
        private const val SCHEMA = 2
        private const val MAX_GM_LEVEL = 3
        private const val RECORD = "account.json"

        /** Realm account rule: length 1..16, ASCII alphanumeric. */
        fun isValidCredential(value: String): Boolean =
            value.length in 1..16 && value.all { it.isLetterOrDigit() && it.code < 128 }

        /**
         * Pure record decoder (the [readLocked] body) so the durable format is
         * unit-testable. G3-1, in lockstep with the Android twin: the flavor
         * rides as an OPTIONAL field at the existing SCHEMA — records that
         * predate the key decode as vanilla and are never quarantined for its
         * absence; only an unknown flavor VALUE is rejected (which quarantines
         * via [loadOrQuarantine], fail-closed against doctored records).
         */
        internal fun decodeRecord(value: JSONObject): UserAccount {
            require(value.getInt("schema") == SCHEMA) { "unsupported user-account schema" }
            val username = value.getString("username")
            val password = value.getString("password")
            require(isValidCredential(username)) { "invalid stored user-account username" }
            require(isValidCredential(password)) { "invalid stored user-account password" }
            val accountId = value.optLong("accountId", 0)
            require(accountId > 0) { "invalid stored user-account id" }
            val gmLevel = value.optInt("gmLevel", -1)
            require(gmLevel in 0..MAX_GM_LEVEL) { "invalid stored user-account privilege" }
            val flavor = value.optString("flavor", ClientFlavors.VANILLA.flavorId)
            require(flavor in ClientFlavors.ALL.map { it.flavorId }) { "invalid stored user-account flavor" }
            return UserAccount(username, password, accountId, gmLevel, flavor)
        }

        /**
         * Pure record encoder (the [writeLocked] body). The flavor key is
         * written ONLY when non-vanilla, so the bytes written for existing
         * vanilla users are unchanged (G3-1 byte-stability).
         */
        internal fun encodeRecord(account: UserAccount): ByteArray {
            val encoded = JSONObject()
                .put("schema", SCHEMA)
                .put("username", account.username)
                .put("password", account.password)
                .put("accountId", account.accountId)
                .put("gmLevel", account.gmLevel)
            if (account.flavor != ClientFlavors.VANILLA.flavorId) encoded.put("flavor", account.flavor)
            return encoded.toString().toByteArray(Charsets.UTF_8)
        }
    }
}
