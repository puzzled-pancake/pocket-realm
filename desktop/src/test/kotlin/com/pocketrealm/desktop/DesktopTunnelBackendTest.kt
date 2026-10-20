package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.server.ServerRuntimeFiles
import com.pocketrealm.supervisor.AdvertisedAddress
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.RealmEndpoint
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.pocketrealm.client.ActiveFlavor
import org.junit.After
import org.junit.Before

/**
 * Design-tunnel §4.4.2/§5 (bind/advertise split) and §9.3 (host ban verb):
 *
 *  - the ADVERTISED address lands in the one realmlist row even when it
 *    diverges from the bind endpoint (a tailnet 100.64/10 address), the
 *    guards refuse honestly (hostname, oversize, public IP), and the
 *    plain-endpoint path keeps writing the endpoint address;
 *  - the conf writers accept the wildcard bind ("0.0.0.0", the raw conf
 *    value tunnel hosting binds) and a wildcard bind is non-loopback, so
 * the lockout rides it;
 *  - the host ban writes the `account_banned` row realmd enforces at the
 *    next logon challenge (AuthSocket.cpp:466-477): permanent = expires_at
 *    == banned_at, timed = expires_at == banned_at + duration.
 *
 * Pure JVM + the SQLite seam DLL — no realm/world natives.
 */
class DesktopTunnelBackendTest {
    /** flip fixture: the sole-qualified degrade is retired; machinery
     * tests that do not exercise the Home pick flow pin the VANILLA lane
     * (holder select) so the resolvers see one honest lane. */
    @Before
    fun pinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
        ActiveFlavor.select("vanilla")
    }

    @After
    fun unpinTheVanillaLaneForTheFlip() {
        ActiveFlavor.reset()
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val seamDll =
        File("../native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll")

    private val owner = ComponentOwner(sessionId = "tunnel-test", instanceToken = "tunnel-test")

    /** Seeds a realmlist table into the flavor-registry realmd database.
     * The DESK-5/47/78 default flip made MARIADB the fresh-init default;
     * these projections seed and assert the SQLITE datadir, so — like
     * every sibling sqlite-lane projection test — they select the
     * provider explicitly instead of riding the pre-flip default. */
    private fun seedRealmlist(roots: DesktopStorageRoots): File {
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            com.pocketrealm.storage.Settings.Snapshot(
                databaseProvider = com.pocketrealm.storage.Settings.DatabaseProvider.SQLITE,
            ).toJson(),
        )
        roots.sqliteDatadir.mkdirs()
        val realmd = DatabaseSqliteControlPlane.databaseFile(
            roots.sqliteDatadir,
            ServerRuntimeFiles.serverDatabases().realmd,
        )
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            db.exec(
                "CREATE TABLE realmlist (id INTEGER PRIMARY KEY, address TEXT, port INTEGER, " +
                    "realmbuilds TEXT NOT NULL DEFAULT '');",
            )
            db.exec("INSERT INTO realmlist VALUES (1, '127.0.0.1', 3724, '');")
        }
        return realmd
    }

    /** Seeds account + account_banned the way the SQLite transcript does. */
    private fun seedAccounts(roots: DesktopStorageRoots): File {
        roots.sqliteDatadir.mkdirs()
        val realmd = DatabaseSqliteControlPlane.databaseFile(
            roots.sqliteDatadir,
            ServerRuntimeFiles.serverDatabases().realmd,
        )
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            db.exec("CREATE TABLE account (id INTEGER PRIMARY KEY, username TEXT);")
            db.exec("INSERT INTO account (id, username) VALUES (7, 'FRIEND451');")
            db.exec(
                "CREATE TABLE account_banned (id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "account_id INTEGER NOT NULL DEFAULT 0, banned_at INTEGER NOT NULL DEFAULT 0, " +
                    "expires_at INTEGER NOT NULL DEFAULT 0, banned_by TEXT NOT NULL, " +
                    "unbanned_at INTEGER NOT NULL DEFAULT 0, unbanned_by TEXT, " +
                    "reason TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 1);",
            )
        }
        return realmd
    }

    // ---- design-tunnel §4.4.2/§5: the advertise projection ---------------

    @Test
    fun tunnelAdvertisementWritesTheAdvertisedRowNotTheBindEndpoint() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val roots = DesktopStorageRoots(folder.newFolder("roots-adv"))
        val realmd = seedRealmlist(roots)
        val backend = DesktopRuntimeBackend(roots)
        val bind = RealmEndpoint.parseLan("192.168.1.50")
        val result = runBlocking {
            backend.projectRealmAdvertisement(owner, bind, "100.64.12.34")
        }
        assertTrue("the tunnel advertisement must project: ${result.detail}", result.ok)
        assertTrue(
            "the divergence is reported in the detail (§5):\n${result.detail}",
            "100.64.12.34" in result.detail && "192.168.1.50" in result.detail,
        )
        DesktopSqliteConnection(realmd.absolutePath).use { db ->
            assertEquals(
                "the row carries the advertised tunnel address, never the LAN IP",
                "100.64.12.34",
                db.queryText("SELECT address FROM realmlist WHERE id = 1;"),
            )
            assertEquals(
                "the tunnel advertisement carries the flavor's accepted builds too",
                "5875 6005 6141",
                db.queryText("SELECT realmbuilds FROM realmlist WHERE id = 1;"),
            )
        }
    }

    @Test
    fun loopbackAdvertisementKeepsTheEndpointOnlyDetail() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val roots = DesktopStorageRoots(folder.newFolder("roots-loop"))
        seedRealmlist(roots)
        val backend = DesktopRuntimeBackend(roots)
        val result = runBlocking {
            backend.projectRealmAdvertisement(owner, RealmEndpoint.LOCAL, RealmEndpoint.LOOPBACK_ADDRESS)
        }
        assertTrue(result.detail, result.ok)
        assertEquals("realmlist -> 127.0.0.1", result.detail)
    }

    @Test
    fun advertisementRefusesHostnamesPublicIpv4AndOversizeHonestly() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val roots = DesktopStorageRoots(folder.newFolder("roots-bad-adv"))
        seedRealmlist(roots)
        val backend = DesktopRuntimeBackend(roots)
        val bind = RealmEndpoint.parseLan("192.168.1.50")
        listOf(
            // The typed parse's own canonical-IPv4 copy (the injection
            // guard's message shape — honest about what was refused).
            "my-realm.ts.net" to "canonical numeric IPv4",
            "8.8.8.8" to "advertised address must be",
            "100.64.0.1.and.a.lot.more.padding.to.bust.the.column" to "longer than",
        ).forEach { (advertised, expected) ->
            val result = runBlocking { backend.projectRealmAdvertisement(owner, bind, advertised) }
            assertFalse("$advertised must refuse: ${result.detail}", result.ok)
            assertTrue(
                "$advertised refusal must carry the honest guard copy: ${result.detail}",
                expected in result.detail,
            )
            assertTrue(
                "refusals keep the stable machine marker: ${result.detail}",
                "REALMLIST-PROJECTION-FAILED" in result.detail,
            )
        }
        DesktopSqliteConnection(realmdFile(roots).absolutePath).use { db ->
            assertEquals(
                "a refused advertisement never touches the row",
                "127.0.0.1",
                db.queryText("SELECT address FROM realmlist WHERE id = 1;"),
            )
        }
    }

    private fun realmdFile(roots: DesktopStorageRoots): File =
        DatabaseSqliteControlPlane.databaseFile(
            roots.sqliteDatadir,
            ServerRuntimeFiles.serverDatabases().realmd,
        )

    @Test
    fun advertisedValueTypeCoversTheTunnelRanges() {
        // The type the settings field validates for display and the launch
        // spec carries (design-tunnel §4.4.1 v1 grammar: numeric only).
        assertEquals("100.64.12.34", AdvertisedAddress.parse("100.64.12.34").value)
        assertTrue(AdvertisedAddress.parse("100.64.12.34").isCgnatTunnel)
        assertFalse(AdvertisedAddress.parse("192.168.1.50").isCgnatTunnel)
    }

    // ---- design-tunnel §9.3: the timed-suspension host verb --------------
    // (The permanent ban/unban lane is DesktopGuestBans — design-lan D5b;
    // this pins the tunnel design's duration-presets form on the same
    // store, so the host has one moderation lane, not two.)

    private fun banStore(roots: DesktopStorageRoots): DesktopGuestBans =
        DesktopGuestBans(realmdDatabase = realmdFile(roots))

    @Test
    fun timedSuspensionExpiresAtBannedAtPlusDuration() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val roots = DesktopStorageRoots(folder.newFolder("roots-ban-timed"))
        seedAccounts(roots)
        val store = banStore(roots)
        val before = System.currentTimeMillis() / 1000 - 1
        val outcome = store.suspendFor("friend451", 3600, "Cooldown")
        assertTrue("the suspension must write: $outcome", outcome is DesktopGuestBans.BanOutcome.Banned)
        DesktopSqliteConnection(realmdFile(roots).absolutePath).use { db ->
            val bannedAt = db.queryText("SELECT banned_at FROM account_banned;").orEmpty().toLong()
            val expiresAt = db.queryText("SELECT expires_at FROM account_banned;").orEmpty().toLong()
            assertTrue(bannedAt >= before)
            assertEquals(bannedAt + 3600, expiresAt)
            assertEquals("1", db.queryText("SELECT active FROM account_banned;"))
        }
        // The store's own listing keeps an unexpired suspension visible and
        // reports it as NOT permanent.
        val rows = store.listBans()
        assertEquals(1, rows.size)
        assertFalse(rows.single().permanent)
    }

    @Test
    fun suspensionRefusesWhileAPermanentBanIsActive() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val roots = DesktopStorageRoots(folder.newFolder("roots-ban-clash"))
        seedAccounts(roots)
        val store = banStore(roots)
        assertTrue(store.ban("friend451", "Banned by host") is DesktopGuestBans.BanOutcome.Banned)
        val outcome = store.suspendFor("friend451", 3600, "Cooldown")
        assertTrue(
            "a suspension must never shorten a permanent ban: $outcome",
            outcome is DesktopGuestBans.BanOutcome.Refused,
        )
        DesktopSqliteConnection(realmdFile(roots).absolutePath).use { db ->
            assertEquals(
                "the permanent form is untouched",
                "1",
                db.queryText("SELECT COUNT(*) FROM account_banned WHERE expires_at = banned_at;"),
            )
        }
    }

    @Test
    fun suspensionRefusesUnknownAccountsAndZeroDurations() {
        requireNativeIfDemanded("sqlite seam", seamDll)
        val roots = DesktopStorageRoots(folder.newFolder("roots-ban-unknown"))
        seedAccounts(roots)
        val store = banStore(roots)
        assertTrue(
            store.suspendFor("nobody0", 3600, "Cooldown")
                is DesktopGuestBans.BanOutcome.Refused,
        )
        try {
            store.suspendFor("friend451", 0, "Cooldown")
            org.junit.Assert.fail("a zero duration must refuse")
        } catch (expected: IllegalArgumentException) {
            assertTrue((expected.message ?: "").contains("positive"))
        }
        DesktopSqliteConnection(realmdFile(roots).absolutePath).use { db ->
            assertEquals("0", db.queryText("SELECT COUNT(*) FROM account_banned;"))
        }
    }
}
