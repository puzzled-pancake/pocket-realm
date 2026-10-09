package com.pocketrealm.desktop

import com.pocketrealm.database.DatabaseSqliteControlPlane
import com.pocketrealm.database.DesktopSqliteConnection
import com.pocketrealm.server.ServerRuntimeContract
import com.pocketrealm.server.WorldNative
import com.pocketrealm.server.PreparedDataStore
import com.pocketrealm.server.ServerRuntimeFiles
import com.pocketrealm.supervisor.ComponentLifecycle
import com.pocketrealm.supervisor.ComponentOwner
import com.pocketrealm.supervisor.FriendRosterJson
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeActionResult
import com.pocketrealm.supervisor.RuntimeComponent
import com.pocketrealm.supervisor.RuntimeLaunchSpec
import com.pocketrealm.supervisor.RuntimeMode
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The native relay ops for remote friend accounts (design-remote-accounts
 * §3/§8/§9/§11), executed for real against the staged
 * pocket_world_runtime.dll and a live world: verifier provisioning (the
 * friend-side derivation via [VsrpMath], no password anywhere), the
 * duplicate-name refusal, the roster read (ban state / failed-logins /
 * last-logon / online), the ban and unban verbs, gmlevel, and per-friend
 * characters — then the backend verb over the same live world.
 *
 * Boots the machine's real stack (the only place a world reaches READY)
 * under the NativeGate + boot-input gates; test accounts are wiped from the
 * datadir after the drain (the AuthGate direct-write precedent).
 */
class FriendAccountNativeOpsTest {
    private val owner = ComponentOwner(sessionId = "friend-ops-test", instanceToken = "friend-ops-test")

    private fun spec() = RuntimeLaunchSpec(
        mode = RuntimeMode.LOCAL,
        profileId = "local",
        endpoint = RealmEndpoint.LOCAL,
        includeClient = false,
    )

    private fun assumeBootableStack(roots: DesktopStorageRoots) {
        requireNativeIfDemanded(
            "pocket_world_runtime",
            File("../native/.build-win-x86_64-mariadb/pocket-runtime-build/pocket_world_runtime.dll"),
        )
        assumeTrue("Windows-only leg", "windows" in System.getProperty("os.name").lowercase())
        runCatching { PreparedDataStore(File(roots.content, "o11-server")).requireActive() }
            .onFailure { failure ->
                assumeTrue("prepared world data not staged: ${failure.message}", false)
            }
        val seeded = DatabaseSqliteControlPlane.DATABASES.all { database ->
            DatabaseSqliteControlPlane.databaseFile(roots.sqliteDatadir, database).isFile
        }
        assumeTrue("datadir not seeded: ${roots.sqliteDatadir}", seeded)
        val sealProof = kotlinx.coroutines.runBlocking {
            runCatching { DesktopRuntimeBackend(roots).preflight(spec()) }
        }.getOrElse { failure ->
            RuntimeActionResult(false, failure.message ?: failure.javaClass.simpleName)
        }
        assumeTrue("datadir not seal-current: ${sealProof.detail}", sealProof.ok)
    }

    // LongMethod/MaxLineLength: one live-world leg covering the five new
    // relay ops end to end — splitting it would multiply the expensive boot
    // the NativeGate gates exist to bound.
    @Test
    @Suppress("LongMethod", "MaxLineLength")
    fun verifierProvisionRosterBanAndCharactersRunAgainstALiveWorld() {
        val roots = DesktopStorageRoots()
        assumeBootableStack(roots)
        val lock = runCatching { AppInstanceLock.acquire(roots.realm) }.getOrElse { failure ->
            assumeTrue("a live Pocket Realm instance owns the runtime domain (${failure.message})", false)
            throw failure
        }
        // Unique per-run identities (1..16 ASCII alnum); wiped in finally.
        // The two draws carry DIFFERENT prefixes on purpose: adjacent
        // nanoTime calls share their top hex digits, so two bare take(8)
        // draws render identical names unless the pair straddles a ~64µs
        // boundary (round-2: both printed FRND25ACD485 and the backend
        // rightly refused friendB with ACCOUNT_EXISTS). The prefix makes
        // the names distinct by construction; the draws keep per-run
        // freshness for the finally-wipe.
        val friendA = "AFRND" + java.lang.Long.toUnsignedString(System.nanoTime(), 16).take(7).uppercase()
        val friendB = "BFRND" + java.lang.Long.toUnsignedString(System.nanoTime(), 16).take(7).uppercase()
        // The mixed-case probe (deliberately NOT uppercase — it exercises
        // the op's normalizeString leg); wiped in finally like the others.
        val mixedName = "MiXeD" + java.lang.Long.toUnsignedString(System.nanoTime(), 16).take(5).uppercase()
        val backend = DesktopRuntimeBackend(roots)
        try {
            kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.DATABASE, owner, spec()) }
            kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.REALM, owner, spec()) }
            kotlinx.coroutines.runBlocking { backend.start(RuntimeComponent.WORLD, owner, spec()) }
            kotlinx.coroutines.runBlocking {
                val deadline = System.currentTimeMillis() + WORLD_READY_WAIT_MS
                while (backend.observe(RuntimeComponent.WORLD).state != ComponentLifecycle.READY) {
                    check(System.currentTimeMillis() < deadline) { "world never reached READY" }
                    Thread.sleep(READY_POLL_MS)
                }
            }

            // ---- §3: the friend derives (s, v) locally; only the hexes cross.
            val verifier = VsrpMath.verifierFor(friendA, "Friend-Pass-1")
            println(
                "PROVISION_INPUT: name=$friendA len=${friendA.length} " +
                    "sLen=${verifier.sHex.length} vLen=${verifier.vHex.length} " +
                    "sHead=${verifier.sHex.take(4)} vHead=${verifier.vHex.take(4)}",
            )
            val provision = WorldNative.createAccountVerifierNative(friendA, verifier.sHex, verifier.vHex)
            org.junit.Assert.assertEquals(
                "verifier provision must succeed: rc=${provision.toList()}",
                0L, provision.getOrElse(0) { -1L },
            )
            org.junit.Assert.assertTrue(
                "the verdict carries the new accountId",
                provision.getOrElse(1) { 0L } > 0,
            )
            val accountId = provision[1]

            // §3.5: the name collision returns ACCOUNT_EXISTS (10); the host
            // rejects the request — no verify-and-adopt for the verifier path.
            val duplicate = WorldNative.createAccountVerifierNative(friendA, verifier.sHex, verifier.vHex)
            org.junit.Assert.assertEquals(
                "the collision is ACCOUNT_EXISTS (10, the shared ABI table)",
                10L,
                duplicate.getOrElse(0) { -1L },
            )

            // §11: the roster read sees the fresh friend at gmlevel 0, not
            // banned, offline, with no logon history and a zero counter.
            val roster = FriendRosterJson.parseAccounts(WorldNative.accountListNative())
            org.junit.Assert.assertNull(roster.first)
            val friendRow = roster.second.single { it.username == friendA }
            org.junit.Assert.assertEquals(accountId, friendRow.id)
            org.junit.Assert.assertEquals(0, friendRow.gmLevel)
            org.junit.Assert.assertFalse(friendRow.banned)
            org.junit.Assert.assertFalse(friendRow.online)
            org.junit.Assert.assertEquals(0L, friendRow.failedLogins)
            org.junit.Assert.assertEquals("", friendRow.lastLogon)

            // §10: the host raises the friend's gmlevel; the roster reflects it.
            org.junit.Assert.assertEquals(
                0,
                WorldNative.setAccountGmLevelNative(friendA, 1, ServerRuntimeContract.CONTROL_TIMEOUT_MS),
            )
            org.junit.Assert.assertEquals(
                1,
                FriendRosterJson.parseAccounts(WorldNative.accountListNative())
                    .second.single { it.username == friendA }.gmLevel,
            )

            // §9: ban through the console relay lands the account_banned row
            // the roster's ban-state column reads; unban clears it.
            val banRaw = WorldNative.banAccountNative(friendA, "1h", "friend-ops test", ServerRuntimeContract.CONTROL_TIMEOUT_MS)
            println("BAN_RAW: $banRaw")
            val banVerdict = FriendRosterJson.parseBanVerdict(banRaw)
            org.junit.Assert.assertTrue("ban must apply: ${banVerdict.second}", banVerdict.first)
            // The second writer may inspect the live row (the WAL allows a
            // second short reader; the DesktopGuestBans precedent).
            val realmd = DatabaseSqliteControlPlane.databaseFile(
                roots.sqliteDatadir,
                ServerRuntimeFiles.serverDatabases().realmd,
            )
            DesktopSqliteConnection(realmd.absolutePath).use { db ->
                println("BANNED_ROWS: " + (db.queryText(
                    "SELECT account_id || ':' || active || ':' || expires_at FROM account_banned;",
                ) ?: "<none>"))
            }
            val rosterAfterBan = FriendRosterJson.parseAccounts(WorldNative.accountListNative())
            println("ROSTER_AFTER_BAN: " + rosterAfterBan.second.joinToString { it.username + "/" + it.banned })
            org.junit.Assert.assertTrue(
                rosterAfterBan.second.single { it.username == friendA }.banned,
            )
            org.junit.Assert.assertTrue(
                FriendRosterJson.parseBanVerdict(
                    WorldNative.unbanAccountNative(friendA, ServerRuntimeContract.CONTROL_TIMEOUT_MS),
                ).first,
            )
            org.junit.Assert.assertFalse(
                FriendRosterJson.parseAccounts(WorldNative.accountListNative())
                    .second.single { it.username == friendA }.banned,
            )

            // The roster's ban flag must track what the engine ENFORCES at
            // logon (AuthSocket.cpp:466-467: active = 1 AND (expires_at >
            // _UNIXTIME_ OR expires_at = banned_at), bigint epoch columns):
            // a TIMED suspension that lapses renders not-banned even though
            // its row is still active=1. The row is written in the engine's
            // own bigint-epoch form (the DesktopGuestBans.suspendFor shape)
            // and lapses while the leg watches.
            val lapseNow = System.currentTimeMillis() / 1000L
            DesktopSqliteConnection(realmd.absolutePath).use { db ->
                db.exec(
                    "INSERT INTO account_banned (account_id, banned_at, expires_at, banned_by, reason, active) VALUES " +
                        "((SELECT id FROM account WHERE username = '$friendA'), " +
                        "$lapseNow, ${lapseNow + 2}, 'friend-ops test', 'lapse test', 1);",
                )
            }
            org.junit.Assert.assertTrue(
                "an unexpired suspension must render as banned",
                FriendRosterJson.parseAccounts(WorldNative.accountListNative())
                    .second.single { it.username == friendA }.banned,
            )
            val lapseDeadline = System.currentTimeMillis() + 20_000L
            var lapsed = false
            while (System.currentTimeMillis() < lapseDeadline) {
                Thread.sleep(500L)
                if (!FriendRosterJson.parseAccounts(WorldNative.accountListNative())
                        .second.single { it.username == friendA }.banned
                ) {
                    lapsed = true
                    break
                }
            }
            DesktopSqliteConnection(realmd.absolutePath).use { db ->
                org.junit.Assert.assertEquals(
                    "the lapsed suspension's row must still be active=1 (the flag changed " +
                        "via the expiry predicate, not a removal)",
                    1L,
                    db.queryLong(
                        "SELECT COUNT(*) FROM account_banned b JOIN account a ON a.id = b.account_id " +
                            "WHERE a.username = '$friendA' AND b.active = 1;",
                    ),
                )
            }
            org.junit.Assert.assertTrue("a lapsed suspension must not render as banned", lapsed)

            // §8: a fresh account owns zero characters; the op answers ok.
            val characters = FriendRosterJson.parseCharacters(WorldNative.accountCharactersNative(friendA))
            org.junit.Assert.assertNull(characters.first)
            org.junit.Assert.assertTrue(characters.second.isEmpty())

            // The backend verb over the same live world (the Players-screen
            // approval path): second friend provisions with an accountId.
            val derivedB = VsrpMath.verifierFor(friendB, "Friend-Pass-2")
            println(
                "PROVISION_INPUT_B: name=$friendB len=${friendB.length} " +
                    "sLen=${derivedB.sHex.length} vLen=${derivedB.vHex.length}",
            )
            val backendVerdict = kotlinx.coroutines.runBlocking {
                backend.provisionAccountVerifier(friendB, derivedB.sHex, derivedB.vHex, false)
            }
            org.junit.Assert.assertTrue(
                "backend verifier provision must succeed: ${backendVerdict.code} " +
                    "${backendVerdict.detail} (sLen=${derivedB.sHex.length} " +
                    "vLen=${derivedB.vHex.length})",
                backendVerdict.ok,
            )
            org.junit.Assert.assertTrue(backendVerdict.accountId > 0)

            // §3.5 name normalization: the friend side derives its verifier
            // from the UPPERCASED identity (Srp6AccountMath) and every logon
            // lookup rides the raw wire name the client uppercases — so the
            // op must store the NORMALIZED form (AccountMgr.cpp:44's
            // normalizeString). A mixed-case provision lands uppercase (the
            // verbatim form never appears on the roster), and its uppercase
            // twin is refused as ACCOUNT_EXISTS — the op is the only
            // case-collision gate the binary-collated, UNIQUE-less SQLite
            // lane has.
            val mixedVerifier = VsrpMath.verifierFor(mixedName.uppercase(), "Friend-Pass-3")
            val mixedRc = WorldNative.createAccountVerifierNative(
                mixedName, mixedVerifier.sHex, mixedVerifier.vHex,
            ).getOrElse(0) { -1L }
            org.junit.Assert.assertEquals("a mixed-case provision succeeds", 0L, mixedRc)
            val rosterNow = FriendRosterJson.parseAccounts(WorldNative.accountListNative()).second
            org.junit.Assert.assertEquals(
                "the row carries the normalized (uppercased) name",
                mixedName.uppercase(),
                rosterNow.single { it.username == mixedName.uppercase() }.username,
            )
            org.junit.Assert.assertTrue(
                "the verbatim mixed-case form must not be a roster row",
                rosterNow.none { it.username == mixedName },
            )
            val twinVerifier = VsrpMath.verifierFor(mixedName.uppercase(), "Friend-Pass-3")
            val twinVerdict = kotlinx.coroutines.runBlocking {
                // R2-1: a first approval (no attempt bookkeeping) landing on
                // an existing account is a COLLISION — refused with no addon
                // write on the existing row (the §3.5 contract restored).
                backend.provisionAccountVerifier(mixedName.uppercase(), twinVerifier.sHex, twinVerifier.vHex, false)
            }
            org.junit.Assert.assertEquals(
                "the uppercase twin of the stored row collides",
                "ACCOUNT_EXISTS",
                twinVerdict.code,
            )
            org.junit.Assert.assertFalse(
                "the collision refusal must not answer ok (the round-2 hole)",
                twinVerdict.ok,
            )

            // A non-loopback bind emits the §4.3/§6.3 lockout keys on this
            // lane's conf (the conditional the remote accounts feature adds
            // to realmd's exposure posture); the LOCAL bind omits them.
            val lanConf = ServerRuntimeFiles(roots).realmdConfig("192.168.1.50").readText()
            org.junit.Assert.assertTrue("WrongPass.MaxCount = 10" in lanConf)
            org.junit.Assert.assertFalse("WrongPass." in kotlinx.coroutines.runBlocking {
                ServerRuntimeFiles(roots).realmdConfig(RealmEndpoint.LOOPBACK_ADDRESS).readText()
            })
        } finally {
            kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.WORLD, owner) }
            kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.REALM, owner) }
            kotlinx.coroutines.runBlocking { backend.stop(RuntimeComponent.DATABASE, owner) }
            // Test hygiene: the wiped identities (the AuthGate direct-write
            // precedent — the stack is drained, so the files are at rest).
            val realmd = DatabaseSqliteControlPlane.databaseFile(
                roots.sqliteDatadir,
                ServerRuntimeFiles.serverDatabases().realmd,
            )
                    if (realmd.isFile) {
                        runCatching {
                            DesktopSqliteConnection(realmd.absolutePath).use { db ->
                                listOf(friendA, friendB, mixedName).forEach { name ->
                            // The op's own bootstrap INSERT rides the account
                            // id — clear it before the identity row.
                            db.exec("DELETE FROM realmcharacters WHERE acctid IN " +
                                "(SELECT id FROM account WHERE username = '$name');")
                            db.exec("DELETE FROM account WHERE username = '$name';")
                        }
                    }
                }
            }
            runCatching { backend.close() }
            runCatching { lock.close() }
        }
    }

    private companion object {
        const val WORLD_READY_WAIT_MS = 180_000L
        const val READY_POLL_MS = 250L
    }
}
