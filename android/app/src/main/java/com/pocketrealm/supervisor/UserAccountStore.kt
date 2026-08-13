package com.pocketrealm.supervisor

import android.content.Context
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Optional user-chosen realm account (O23). Unlike [SinglePlayerCredentialStore]
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
    ): UserAccount = synchronized(lock) {
        require(isValidCredential(username)) { "invalid user-account username" }
        require(isValidCredential(password)) { "invalid user-account password" }
        require(accountId > 0) { "user-account id is invalid" }
        require(gmLevel in 0..3) { "user-account privilege is invalid" }
        val account = UserAccount(username, password, accountId, gmLevel)
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

    fun clear(): Unit = synchronized(lock) {
        if (recordFile.isFile) check(recordFile.delete()) { "stored user account could not be removed" }
        syncDirectoryLocked()
    }

    internal fun fileForTest(): File = recordFile

    private fun readLocked(): UserAccount? {
        if (!recordFile.isFile) return null
        val value = JSONObject(recordFile.readText(Charsets.UTF_8))
        require(value.getInt("schema") == SCHEMA) { "unsupported user-account schema" }
        val username = value.getString("username")
        val password = value.getString("password")
        require(isValidCredential(username)) { "invalid stored user-account username" }
        require(isValidCredential(password)) { "invalid stored user-account password" }
        val accountId = value.optLong("accountId", 0)
        require(accountId > 0) { "invalid stored user-account id" }
        val gmLevel = value.optInt("gmLevel", -1)
        require(gmLevel in 0..3) { "invalid stored user-account privilege" }
        return UserAccount(username, password, accountId, gmLevel)
    }

    private fun writeLocked(account: UserAccount) {
        directory.mkdirs()
        Os.chmod(directory.absolutePath, OWNER_DIRECTORY_MODE)
        val temp = File(directory, ".user-account.${android.os.Process.myPid()}.tmp")
        val encoded = JSONObject()
            .put("schema", SCHEMA)
            .put("username", account.username)
            .put("password", account.password)
            .put("accountId", account.accountId)
            .put("gmLevel", account.gmLevel)
            .toString()
            .toByteArray(Charsets.UTF_8)
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
    ) {
        override fun toString(): String =
            "UserAccount(username=<redacted>, password=<redacted>, accountId=$accountId, gmLevel=$gmLevel)"
    }

    companion object {
        private const val SCHEMA = 2
        private const val DIRECTORY = "user-account"
        private const val RECORD = "account.json"
        private val OWNER_FILE_MODE = OsConstants.S_IRUSR or OsConstants.S_IWUSR
        private val OWNER_DIRECTORY_MODE = OWNER_FILE_MODE or OsConstants.S_IXUSR

        /** Realm account rule: length 1..16, ASCII alphanumeric. */
        fun isValidCredential(value: String): Boolean =
            value.length in 1..16 && value.all { it.isLetterOrDigit() && it.code < 128 }
    }
}
