package com.pocketrealm.desktop

import com.pocketrealm.database.DesktopSqliteConnection
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * D5b as resolved with the run owner (partial: ban/unban as DIRECT
 * `account_banned` rows; kick deferred on the native seam): the store's
 * rows must satisfy the ENGINE'S OWN logon-challenge predicate
 * (AuthSocket.cpp:466-477: `active = 1 AND (expires_at > now OR
 * expires_at = banned_at)` — a surviving row means realmd refuses the
 * logon with AUTH_LOGON_FAILED_BANNED). The pins replay that exact SQL.
 *
 * Skips (like the seam test) when the pocket_sqlite seam is not staged.
 */
class DesktopGuestBansTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var realmd: File
    private lateinit var bans: DesktopGuestBans

    @Before
    fun stageSeededRealmd() {
        requireNativeIfDemanded(
            "sqlite seam",
            File("..").resolve("native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll"),
        )
        realmd = File(folder.newFolder("datadir"), "realmd.sqlite3")
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            db.exec(
                "CREATE TABLE account (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "username TEXT NOT NULL DEFAULT '');",
            )
            db.exec(
                "CREATE TABLE account_banned (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "account_id INTEGER NOT NULL DEFAULT 0, " +
                    "banned_at BIGINT NOT NULL DEFAULT 0, " +
                    "expires_at BIGINT NOT NULL DEFAULT 0, " +
                    "banned_by TEXT NOT NULL DEFAULT '', " +
                    "unbanned_at BIGINT NOT NULL DEFAULT 0, " +
                    "unbanned_by TEXT DEFAULT NULL, " +
                    "reason TEXT NOT NULL DEFAULT '', " +
                    "active INTEGER NOT NULL DEFAULT 0);",
            )
            db.exec("INSERT INTO account (id, username) VALUES (7, 'FRIENDONE'), (8, 'FRIENDTWO');")
        }
        bans = DesktopGuestBans(realmdDatabase = realmd, clockSeconds = { 1_700_000_000L })
    }

    /** The engine's own challenge predicate: non-null = the logon is
     * refused (AUTH_LOGON_FAILED_BANNED). */
    private fun engineRefusesLogon(accountId: Long, nowSeconds: Long): Boolean =
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            db.queryText(
                "SELECT 'banned' FROM account_banned WHERE account_id = $accountId " +
                    "AND active = 1 AND (expires_at > $nowSeconds OR expires_at = banned_at);",
            ) != null
        }

    @Test
    fun banRefusesTheLogonChallenge() {
        val outcome = bans.ban("FriendOne", "griefing")
        assertTrue(
            "the ban must succeed: $outcome",
            outcome is DesktopGuestBans.BanOutcome.Banned && !outcome.alreadyBanned,
        )
        assertEquals(7L, (outcome as DesktopGuestBans.BanOutcome.Banned).accountId)
        // The permanent form: expires_at == banned_at — refused forever.
        val row = bans.listBans().single()
        assertEquals("FRIENDONE", row.username)
        assertEquals("griefing", row.reason)
        assertTrue(row.permanent)
        assertTrue(
            "realmd must refuse the logon now and later",
            engineRefusesLogon(7L, 1_700_000_000L) && engineRefusesLogon(7L, 1_700_000_000L + 3_600),
        )
    }

    @Test
    fun unbanAllowsTheLogonAgain() {
        bans.ban("FriendOne", "griefing")
        val outcome = bans.unban("FRIENDONE")
        assertTrue(
            "the unban must report a lifted ban: $outcome",
            outcome is DesktopGuestBans.BanOutcome.Unbanned && outcome.wasBanned,
        )
        assertTrue("no active ban remains", bans.listBans().isEmpty())
        assertFalse(
            "realmd must admit the logon again",
            engineRefusesLogon(7L, 1_700_000_000L),
        )
    }

    @Test
    fun banOfAnUnknownAccountFailsLoud() {
        val outcome = bans.ban("NOBODY", "typo")
        assertTrue(
            "an unknown name must refuse, never fake a ban: $outcome",
            outcome is DesktopGuestBans.BanOutcome.Refused && "no account" in outcome.detail,
        )
        assertTrue(bans.listBans().isEmpty())
    }

    @Test
    fun aSecondBanIsIdempotentAndLeavesOneActiveRow() {
        bans.ban("FriendOne", "first")
        val outcome = bans.ban("FriendOne", "second")
        assertTrue(
            "the second ban reports already-banned: $outcome",
            outcome is DesktopGuestBans.BanOutcome.Banned && outcome.alreadyBanned,
        )
        assertEquals(1, bans.listBans().size)
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            assertEquals(
                1L,
                db.queryLong("SELECT COUNT(*) FROM account_banned WHERE account_id = 7;"),
            )
        }
    }

    @Test
    fun namesViolatingTheTokenRuleRefuse() {
        listOf("has space", "mindé", "").forEach { name ->
            val outcome = bans.ban(name, "reason")
            assertTrue(
                "the token rule must refuse '$name': $outcome",
                outcome is DesktopGuestBans.BanOutcome.Refused,
            )
        }
    }

    @Test
    fun listBansDropsLiftedRowsAndJoinsUsernames() {
        bans.ban("FriendOne", "one")
        bans.ban("friendtwo", "two")
        bans.unban("friendtwo")
        val listed = bans.listBans()
        assertEquals(listOf("FRIENDONE"), listed.map { it.username })
        assertNotNull(listed.single().bannedAt)
    }

    @Test
    fun banSucceedsWhileAnotherWriterHoldsTheDatabase() {
        // The load-bearing pragma pin: realmd writes this same database at
        // every logon challenge, so a ban issued while a writer holds the
        // DB must WAIT out the writer (busy_timeout from the repo's pragma
        // policy) instead of failing with SQLITE_BUSY. The holder keeps its
        // exclusive transaction for 300 ms — well inside the 500 ms
        // busy_timeout, well outside any polite poll.
        val holder = DesktopSqliteConnection(realmd.absolutePath)
        val committed = java.util.concurrent.CountDownLatch(1)
        val writer = Thread {
            holder.use { db ->
                com.pocketrealm.database.DatabaseSqliteConfigPolicy
                    .renderConnectionPragmas().forEach(db::queryText)
                db.exec("BEGIN EXCLUSIVE;")
                db.exec(
                    "INSERT INTO account_banned (account_id, banned_at, expires_at, banned_by, reason, active) " +
                        "VALUES (8, 1, 1, 'held', 'holds the write lock', 1);",
                )
                Thread.sleep(300)
                db.exec("COMMIT;")
            }
            committed.countDown()
        }
        writer.start()
        Thread.sleep(100) // the holder owns the exclusive lock by now
        val outcome = bans.ban("FriendOne", "issued against a busy database")
        assertTrue(
            "the ban must wait out the concurrent writer, never fail locked: $outcome",
            outcome is DesktopGuestBans.BanOutcome.Banned && !outcome.alreadyBanned,
        )
        assertTrue(committed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        writer.join(5_000)
        assertEquals(
            "both writes landed: the holder's row and the ban",
            2L,
            DesktopSqliteConnection(realmd.absolutePath).use { db ->
                db.queryLong("SELECT COUNT(*) FROM account_banned;")
            },
        )
    }

    @Test
    fun aMissingDatabaseRefusesHonestly() {
        val absent = DesktopGuestBans(realmdDatabase = File(folder.newFolder("empty"), "none.sqlite3"))
        val outcome = absent.ban("FriendOne", "reason")
        assertTrue(
            "no datadir = honest refusal: $outcome",
            outcome is DesktopGuestBans.BanOutcome.Refused,
        )
    }
}
