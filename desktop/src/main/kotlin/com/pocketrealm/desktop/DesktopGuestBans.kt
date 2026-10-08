package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteConfigPolicy
import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.server.ServerRuntimeContract
import java.io.File

/**
 * D5b (design-lan §4.3), as resolved with the run owner: the guest
 * revocation store — ban/unban implemented as DIRECT rows on the realmd
 * database's `account_banned` table, NOT through the engine console relay.
 * The console relay (`issue_command`, world_runtime.cpp:1130-1152) has no
 * JNI export in the pinned pocket_world_runtime.dll, and widening the
 * pinned runtime is an engine release outside this lane; a direct row needs
 * no native change because realmd enforces `account_banned` at every logon
 * challenge (AuthSocket.cpp:466-477: `active = 1 AND (expires_at > now OR
 * expires_at = banned_at)` — equality is the engine's PERMANENT form and
 * answers AUTH_LOGON_FAILED_BANNED).
 *
 * Deferred with this resolution: KICK (disconnecting an already-online
 * guest) — a live session cannot be ejected from the database; it needs the
 * console relay (`kick`, SEC_GAMEMASTER) and stays blocked on the native
 * seam widening. The UI shows kick as unavailable with honest copy.
 *
 * Ban/unban here is a HOST-DB act, not a world-control op: it works while
 * the realm runs (WAL allows the second short writer; the world's own
 * connection keeps checkpointing) and while it is stopped. The schema is
 * the seed transcript's exact `account_banned` DDL (migration 0001).
 */
internal class DesktopGuestBans(
    /** The realmd SQLite database file (the seeded datadir's realmd lane). */
    private val realmdDatabase: File,
    /** Wall clock in engine seconds (the banned_at/expires_at unit). */
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / MS_PER_SECOND },
    /** The ban attribution recorded in the row (the schema's banned_by). */
    private val bannedBy: String = "PocketRealm host",
    /** design-remote-accounts §12: the host audit sink. Every settled
     * ban/suspend/unban appends a row (action, username, accountId,
     * outcome) to the same no-secret audit store the invite flow writes;
     * the default is a no-op so existing constructions stay valid. */
    private val audit: (action: String, username: String, accountId: Long, outcome: String) -> Unit =
        { _, _, _, _ ->
        },
) {

    /** One active ban as listed to the host. */
    data class BanRow(
        val accountId: Long,
        val username: String,
        val bannedAt: Long,
        val expiresAt: Long,
        val reason: String,
    ) {
        /** The engine's permanent form: expires_at == banned_at. */
        val permanent: Boolean get() = expiresAt == bannedAt
    }

    sealed class BanOutcome {
        /** The row was written (or the ban was already in place). */
        data class Banned(val accountId: Long, val alreadyBanned: Boolean) : BanOutcome()

        /** The active row was cleared (or nothing was active). */
        data class Unbanned(val accountId: Long, val wasBanned: Boolean) : BanOutcome()

        /** The honest refusal (unknown account, bad input, missing DB). */
        data class Refused(val detail: String) : BanOutcome()
    }

    /**
     * Permanently ban [username] with a [reason] (the engine's permanent
     * form: `expires_at = banned_at`, refused at the next logon challenge
     * with AUTH_LOGON_FAILED_BANNED). Fails loud on an unknown account —
     * a ban of a name that does not exist must never silently "succeed".
     */
    @Suppress("ReturnCount") // the honest-refusal returns ARE the gate's shape
    fun ban(username: String, reason: String): BanOutcome {
        val nameError = tokenRefusal(username)
        if (nameError != null) return BanOutcome.Refused(nameError)
        if (reason.length > MAX_REASON_CHARS) {
            return BanOutcome.Refused("reason must be at most $MAX_REASON_CHARS characters")
        }
        val id = accountIdFor(username)
            ?: return BanOutcome.Refused(
                "no account named '$username' exists on this realm — guest accounts are " +
                    "created on the Guests card while the realm is hosting",
            )
        val now = clockSeconds()
        return write("ban '$username'") { db ->
            val active = db.queryLong(
                "SELECT COUNT(*) FROM account_banned WHERE account_id = $id AND active = 1;",
            ) ?: 0L
            if (active > 0) {
                audit(AUDIT_BANNED, username, id, "already-banned")
                BanOutcome.Banned(id, alreadyBanned = true)
            } else {
                db.exec(
                    "INSERT INTO account_banned " +
                        "(account_id, banned_at, expires_at, banned_by, reason, active) VALUES " +
                        "($id, $now, $now, '${escape(bannedBy)}', '${escape(reason.trim())}', 1);",
                )
                audit(AUDIT_BANNED, username, id, "permanent: " + reason.trim())
                BanOutcome.Banned(id, alreadyBanned = false)
            }
        }
    }

    /**
     * Design-tunnel §9.3: the timed-suspension preset — an active row that
     * EXPIRES ([durationSeconds] from now), so realmd's logon gate
     * (AuthSocket.cpp:466-477: `expires_at > now OR expires_at = banned_at`)
     * admits the friend again once the suspension lapses, with no host
     * action. Refused for an already-active ban (clear it first — the UI's
     * unban button) so a suspension can never silently shorten a permanent
     * ban's hold.
     */
    @Suppress("ReturnCount") // the honest-refusal returns ARE the gate's shape
    fun suspendFor(username: String, durationSeconds: Long, reason: String): BanOutcome {
        require(durationSeconds > 0) { "suspension duration must be positive" }
        val nameError = tokenRefusal(username)
        if (nameError != null) return BanOutcome.Refused(nameError)
        if (reason.length > MAX_REASON_CHARS) {
            return BanOutcome.Refused("reason must be at most $MAX_REASON_CHARS characters")
        }
        val id = accountIdFor(username)
            ?: return BanOutcome.Refused(
                "no account named '$username' exists on this realm — guest accounts are " +
                    "created on the Guests card while the realm is hosting",
            )
        val now = clockSeconds()
        return write("suspend '$username'") { db ->
            val active = db.queryLong(
                "SELECT COUNT(*) FROM account_banned WHERE account_id = $id AND active = 1;",
            ) ?: 0L
            if (active > 0) {
                BanOutcome.Refused(
                    "'$username' already has an active ban — lift it (unban) before suspending",
                )
            } else {
                db.exec(
                    "INSERT INTO account_banned " +
                        "(account_id, banned_at, expires_at, banned_by, reason, active) VALUES " +
                        "($id, $now, ${now + durationSeconds}, '${escape(bannedBy)}', " +
                        "'${escape(reason.trim())}', 1);",
                )
                audit(AUDIT_BANNED, username, id, "suspension ${durationSeconds}s: " + reason.trim())
                BanOutcome.Banned(id, alreadyBanned = false)
            }
        }
    }

    /** Clear every active ban row for [username] (the engine's `unban`). */
    @Suppress("ReturnCount") // the honest-refusal returns ARE the gate's shape
    fun unban(username: String): BanOutcome {
        val nameError = tokenRefusal(username)
        if (nameError != null) return BanOutcome.Refused(nameError)
        val id = accountIdFor(username)
            ?: return BanOutcome.Refused("no account named '$username' exists on this realm")
        val now = clockSeconds()
        return write("unban '$username'") { db ->
            val wasBanned = (db.queryLong(
                "SELECT COUNT(*) FROM account_banned WHERE account_id = $id AND active = 1;",
            ) ?: 0L) > 0
            db.exec(
                "UPDATE account_banned SET active = 0, unbanned_at = $now, " +
                    "unbanned_by = '${escape(bannedBy)}' WHERE account_id = $id AND active = 1;",
            )
            audit(AUDIT_UNBANNED, username, id, if (wasBanned) "unbanned" else "was-not-banned")
            BanOutcome.Unbanned(id, wasBanned = wasBanned)
        }
    }

    /** Every active ban (newest first), joined to the account's username.
     * The seam is scalar-query only, so each row rides one concatenated
     * query (the username is ASCII-alnum — the '|' separator is safe, and
     * the reason is read last with a bounded split). */
    fun listBans(): List<BanRow> {
        if (!realmdDatabase.isFile) return emptyList()
        return runCatching {
            DesktopSqliteConnection(realmdDatabase.absolutePath).use { db ->
                DatabaseSqliteConfigPolicy.renderConnectionPragmas().forEach(db::queryText)
                val total = db.queryLong(
                    "SELECT COUNT(*) FROM account_banned b JOIN account a ON a.id = b.account_id " +
                        "WHERE b.active = 1 AND (b.expires_at > ${clockSeconds()} OR b.expires_at = b.banned_at);",
                ) ?: 0L
                (0L until total).mapNotNull { offset ->
                    // The engine's own permanent-ban predicate keeps an
                    // expired suspension out of the list.
                    val encoded = db.queryText(
                        "SELECT b.account_id || '|' || a.username || '|' || b.banned_at || '|' || " +
                            "b.expires_at || '|' || b.reason " +
                            "FROM account_banned b JOIN account a ON a.id = b.account_id " +
                            "WHERE b.active = 1 AND (b.expires_at > ${clockSeconds()} OR b.expires_at = b.banned_at) " +
                            "ORDER BY b.banned_at DESC, b.account_id DESC LIMIT 1 OFFSET $offset;",
                    ) ?: return@mapNotNull null
                    val parts = encoded.split("|", limit = ENCODING_PARTS)
                    if (parts.size != ENCODING_PARTS) return@mapNotNull null
                    BanRow(
                        accountId = parts[0].toLongOrNull() ?: return@mapNotNull null,
                        username = parts[1],
                        bannedAt = parts[2].toLongOrNull() ?: return@mapNotNull null,
                        expiresAt = parts[3].toLongOrNull() ?: return@mapNotNull null,
                        reason = parts[4],
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** The shared account-token rule (1..16 ASCII alnum, the engine's
     * identity shape) as a refusal string; null = the name is usable.
     * Null input is its own refusal. */
    private fun tokenRefusal(username: String?): String? {
        if (username == null) return "username must not be empty"
        return runCatching {
            ServerRuntimeContract.requireAccountToken("username", username.trim())
            null
        }.getOrElse { failure -> failure.message }
    }

    /** The account row id for a username (ASCII case-insensitive, the
     * engine's uppercase storage), or null when the account is unknown. */
    private fun accountIdFor(username: String): Long? {
        if (!realmdDatabase.isFile) return null
        return runCatching {
            DesktopSqliteConnection(realmdDatabase.absolutePath).use { db ->
                DatabaseSqliteConfigPolicy.renderConnectionPragmas().forEach(db::queryText)
                db.queryLong(
                    "SELECT id FROM account WHERE UPPER(username) = UPPER('${escape(username.trim())}');",
                )
            }
        }.getOrNull()
    }

    /** The shared write shape: honest Refused on any failure, never a
     * throw into the UI path. */
    private inline fun write(what: String, block: (DesktopSqliteConnection) -> BanOutcome): BanOutcome {
        if (!realmdDatabase.isFile) {
            return BanOutcome.Refused("the realm database is not seeded yet")
        }
        return runCatching {
            DesktopSqliteConnection(realmdDatabase.absolutePath).use { db ->
                // busy_timeout is the load-bearing pragma: realmd writes this
                // same database at every logon challenge (the exact scenario
                // bans exist for) and the JNI seam sets no default timeout —
                // without it a concurrent logon failed every ban with
                // SQLITE_BUSY. The block runs in one BEGIN IMMEDIATE
                // transaction, so the check-then-insert legs are atomic and
                // two concurrent host actions cannot double-insert rows.
                DatabaseSqliteConfigPolicy.renderConnectionPragmas().forEach(db::queryText)
                db.exec("BEGIN IMMEDIATE;")
                try {
                    block(db).also { db.exec("COMMIT;") }
                } catch (failure: Throwable) {
                    runCatching { db.exec("ROLLBACK;") }
                    throw failure
                }
            }
        }.getOrElse { failure ->
            BanOutcome.Refused("$what failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    private companion object {
        /** Wall-milliseconds to engine-seconds. */
        const val MS_PER_SECOND = 1_000L

        /** The listBans row encoding: id|username|banned_at|expires_at|reason. */
        const val ENCODING_PARTS = 5

        /** The schema's reason varchar(255); the store caps below it. */
        const val MAX_REASON_CHARS = 200

        /** The seam's exec interpolates (no bind parameters) — single-quote
         * doubling closes the string contexts the store writes. */
        fun escape(value: String): String = value.replace("'", "''")

        /** The §12 audit actions (the FriendInviteStore's no-secret rows). */
        const val AUDIT_BANNED = "friend-banned"
        const val AUDIT_UNBANNED = "friend-unbanned"
    }
}
