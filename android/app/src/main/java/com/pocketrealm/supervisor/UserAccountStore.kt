package com.pocketrealm.supervisor

import android.content.Context
import android.system.Os
import android.system.OsConstants
import com.pocketrealm.client.ClientFlavors
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Optional user-chosen realm account. Unlike [SinglePlayerCredentialStore]
 * (a generated random identity used only for the bot/single-player fallback),
 * this stores an account the user typed into the Home screen so the integrated
 * client can auto-login as that identity on launch.
 *
 * The secret never enters the supervisor journal, logs, status JSON, or evidence.
 * Stored only in noBackup storage with owner-read/write permissions, written
 * atomically (temp + fd.sync + rename + directory fsync) exactly like the
 * single-player store. Validation reuses the realm account rules
 * (`DurableRuntimeSupervisor.provisionAccount`: 1..16 ASCII alphanumeric).
 *
 * The supervisor process only ever checks existence (`loadProvisioned() != null`);
 * the password is read into process memory solely to hand it to the integrated
 * client display for the login keystrokes.
 */
class UserAccountStore(context: Context) {
    private val lock = Any()
    private val directory = File(context.noBackupFilesDir, DIRECTORY)
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
     * — a corrupt load, a stale record — where triage needs the bytes; an
     * intentional clear revokes that retention.)
     */
    fun clear(): Unit = synchronized(lock) {
        if (recordFile.isFile) check(recordFile.delete()) { "stored user account could not be removed" }
        directory.listFiles { file -> file.name.startsWith("account.invalid.") }
            ?.forEach { file -> runCatching { file.delete() } }
        syncDirectoryLocked()
    }

    internal fun fileForTest(): File = recordFile

    private fun readLocked(): UserAccount? {
        if (!recordFile.isFile) return null
        return decodeRecord(JSONObject(recordFile.readText(Charsets.UTF_8)))
    }

    private fun writeLocked(account: UserAccount) {
        directory.mkdirs()
        Os.chmod(directory.absolutePath, OWNER_DIRECTORY_MODE)
        val temp = File(directory, ".user-account.${android.os.Process.myPid()}.tmp")
        val encoded = encodeRecord(account)
        FileOutputStream(temp).use { stream ->
            stream.write(encoded)
            stream.fd.sync()
        }
        Os.chmod(temp.absolutePath, OWNER_FILE_MODE)
        Os.rename(temp.absolutePath, recordFile.absolutePath)
        syncDirectoryLocked()
    }

    private fun quarantineLocked() {
        if (!recordFile.isFile) return
        directory.mkdirs()
        val quarantine = File(directory, "account.invalid.${System.currentTimeMillis()}.json")
        Os.rename(recordFile.absolutePath, quarantine.absolutePath)
        Os.chmod(quarantine.absolutePath, OWNER_FILE_MODE)
        directory.listFiles { file -> file.name.startsWith("account.invalid.") }
            ?.sortedByDescending(File::lastModified)
            ?.drop(2)
            ?.forEach(File::delete)
        syncDirectoryLocked()
    }

    private fun syncDirectoryLocked() {
        if (!directory.isDirectory) return
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
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
        private const val DIRECTORY = "user-account"
        private const val RECORD = "account.json"
        private val OWNER_FILE_MODE = OsConstants.S_IRUSR or OsConstants.S_IWUSR
        private val OWNER_DIRECTORY_MODE = OWNER_FILE_MODE or OsConstants.S_IXUSR

        /** Realm account rule: length 1..16, ASCII alphanumeric. */
        fun isValidCredential(value: String): Boolean =
            value.length in 1..16 && value.all { it.isLetterOrDigit() && it.code < 128 }

        /**
         * Pure record decoder (the [readLocked] body) so the durable format is
         * unit-testable on the JVM. G3-1: the flavor rides as an OPTIONAL
         * field at the existing SCHEMA — records that predate the key decode
         * as vanilla and are never quarantined for its absence; only an
         * unknown flavor VALUE is rejected (which quarantines via
         * [loadOrQuarantine], fail-closed against doctored records).
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
