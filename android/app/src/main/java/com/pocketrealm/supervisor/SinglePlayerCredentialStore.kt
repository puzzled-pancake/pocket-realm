package com.pocketrealm.supervisor

import android.content.Context
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom

/**
 * App-owned credentials for the local single-player account. The secret never
 * enters the supervisor journal, logs, status JSON, or evidence. It is stored
 * only in noBackup storage with owner-read/write permissions.
 */
class SinglePlayerCredentialStore(
    context: Context,
    private val random: SecureRandom = SecureRandom(),
) {
    private val lock = Any()
    private val directory = File(context.noBackupFilesDir, DIRECTORY)
    private val recordFile = File(directory, RECORD)

    fun loadOrCreate(): Credentials = synchronized(lock) {
        readLocked() ?: Credentials(
            username = randomToken(USERNAME_PREFIX, USERNAME_RANDOM_LENGTH),
            password = randomToken("", PASSWORD_LENGTH),
            provisioned = false,
            accountId = 0,
        ).also(::writeLocked)
    }

    fun markProvisioned(credentials: Credentials, accountId: Long): Credentials = synchronized(lock) {
        require(accountId > 0) { "single-player account id is invalid" }
        val current = requireNotNull(readLocked()) { "single-player credential record is missing" }
        require(current.sameSecret(credentials)) { "single-player credential record changed" }
        current.copy(provisioned = true, accountId = accountId).also(::writeLocked)
    }

    /**
     * Abandon an uncommitted random identity after ACCOUNT_EXISTS. This makes a
     * crash between database creation and [markProvisioned] recoverable without
     * ever claiming that an existing account accepts our stored password.
     */
    fun rotateUnprovisioned(credentials: Credentials): Credentials = synchronized(lock) {
        val current = requireNotNull(readLocked()) { "single-player credential record is missing" }
        require(current.sameSecret(credentials) && !current.provisioned) {
            "single-player credential rotation requires the current pending record"
        }
        Credentials(
            username = randomToken(USERNAME_PREFIX, USERNAME_RANDOM_LENGTH),
            password = randomToken("", PASSWORD_LENGTH),
            provisioned = false,
            accountId = 0,
        ).also(::writeLocked)
    }

    fun loadProvisioned(): Credentials? = synchronized(lock) {
        readLocked()?.takeIf { it.provisioned }
    }

    internal fun fileForTest(): File = recordFile

    private fun readLocked(): Credentials? {
        if (!recordFile.isFile) return null
        val value = JSONObject(recordFile.readText(Charsets.UTF_8))
        require(value.getInt("schema") == SCHEMA) { "unsupported single-player credential schema" }
        val username = value.getString("username")
        val password = value.getString("password")
        require(USERNAME.matches(username)) { "invalid stored single-player username" }
        require(PASSWORD.matches(password)) { "invalid stored single-player password" }
        val provisioned = value.getBoolean("provisioned")
        val accountId = value.optLong("accountId", 0)
        require(!provisioned || accountId > 0) { "invalid stored single-player account id" }
        return Credentials(username, password, provisioned, accountId)
    }

    private fun writeLocked(credentials: Credentials) {
        directory.mkdirs()
        Os.chmod(directory.absolutePath, OWNER_DIRECTORY_MODE)
        val temp = File(directory, ".credentials.${android.os.Process.myPid()}.tmp")
        val encoded = JSONObject()
            .put("schema", SCHEMA)
            .put("username", credentials.username)
            .put("password", credentials.password)
            .put("provisioned", credentials.provisioned)
            .put("accountId", credentials.accountId)
            .toString()
            .toByteArray(Charsets.UTF_8)
        FileOutputStream(temp).use { stream ->
            stream.write(encoded)
            stream.fd.sync()
        }
        Os.chmod(temp.absolutePath, OWNER_FILE_MODE)
        Os.rename(temp.absolutePath, recordFile.absolutePath)
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    private fun randomToken(prefix: String, randomLength: Int): String = buildString {
        append(prefix)
        repeat(randomLength) { append(ALPHANUM[random.nextInt(ALPHANUM.length)]) }
    }

    /** Deliberately not a data class: default toString must never reveal the secret. */
    class Credentials internal constructor(
        val username: String,
        val password: String,
        val provisioned: Boolean,
        val accountId: Long,
    ) {
        internal fun copy(provisioned: Boolean, accountId: Long) =
            Credentials(username, password, provisioned, accountId)
        internal fun sameSecret(other: Credentials): Boolean =
            username == other.username && password == other.password

        override fun toString(): String =
            "Credentials(username=<redacted>, password=<redacted>, provisioned=$provisioned)"
    }

    companion object {
        private const val SCHEMA = 1
        private const val DIRECTORY = "single-player"
        private const val RECORD = "credentials.json"
        private const val USERNAME_PREFIX = "PR"
        private const val USERNAME_RANDOM_LENGTH = 10
        private const val PASSWORD_LENGTH = 16
        private const val ALPHANUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val USERNAME = Regex("PR[A-Z2-9]{10}")
        private val PASSWORD = Regex("[A-Z2-9]{16}")
        private val OWNER_FILE_MODE = OsConstants.S_IRUSR or OsConstants.S_IWUSR
        private val OWNER_DIRECTORY_MODE = OWNER_FILE_MODE or OsConstants.S_IXUSR
    }
}
