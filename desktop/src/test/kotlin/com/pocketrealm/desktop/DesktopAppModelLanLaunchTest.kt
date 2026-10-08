package com.pocketrealm.desktop

import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import org.junit.rules.TemporaryFolder

/**
 * DESK-15/24/42 launch-gate contract at the app-model level, plus the
 * design-lan D1 legs:
 *
 *  - DESK-15: `allowLanPlayers` IS the hosting gate — a LAN_HOST start with
 *    the stored toggle off is refused before the supervisor is engaged
 *    (the shared lanHost() factory hard-codes authorization, so the model
 *    builds the spec directly from the stored toggle); the backend's
 *    defensive twin re-proves it (D1).
 *  - DESK-24: join mode = stop realm/world + launch the client against
 *    the remote IP — a LAN_JOIN start while a local runtime is up stops
 *    that runtime first (graceful) and then joins. D1: the join preflight
 *    runs CLIENT PREREQUISITES ONLY (no datadir legs), mirroring the
 *    Android early return.
 *  - D1: a LAN_HOST start re-proves the stored authorization and that the
 *    endpoint is a currently-live private interface (through the D3
 *    filtered view) after the datadir gates pass.
 */
class DesktopAppModelLanLaunchTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun writeSettings(roots: DesktopStorageRoots, snapshot: Settings.Snapshot) {
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(snapshot.toJson())
    }

    /** Seeds an ACTIVE local generation (clean WORLD_READY) into the
     * journal the model will load — the "realm is up" state. The session id
     * must be a UUID (the shared codec validates the format). */
    private fun seedActiveLocalGeneration(roots: DesktopStorageRoots) {
        DesktopSupervisorJournal(roots.supervisorJournalDir).write(
            RuntimeSnapshot(
                sessionId = "12345678-1234-1234-1234-123456789abc",
                requestedProfile = DesktopAppModel.DESKTOP_PROFILE,
                runtimeMode = RuntimeMode.LOCAL,
                phase = RuntimePhase.WORLD_READY,
                clean = true,
                lastDurableAction = "world-ready-client-not-requested",
            ),
        )
    }

    @Test
    fun hostingWithoutTheStoredAuthorizationIsRefused() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-host-off"))
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LAN_HOST,
                lanAddress = "192.168.1.50",
                allowLanPlayers = false,
            ),
        )
        DesktopAppModel(roots).use { model ->
            val op = runBlocking { model.startRealm(includeClient = false) }
            assertFalse("hosting with the toggle off must refuse: ${op.detail}", op.ok)
            assertTrue("the refusal must be the launch gate: ${op.detail}", "launch refused" in op.detail)
            assertTrue(
                "the refusal must name the authorization: ${op.detail}",
                "allowLanPlayers" in op.detail,
            )
        }
    }

    @Test
    fun hostingWithTheStoredAuthorizationReachesTheSupervisor() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-host-on"))
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LAN_HOST,
                lanAddress = "192.168.1.50",
                allowLanPlayers = true,
                // D19: the unseeded-datadir refusal this leg pins is the
                // SQLITE dev/compat lane's; select it explicitly (the
                // shipped default is MariaDB).
                databaseProvider = Settings.DatabaseProvider.SQLITE,
            ),
        )
        DesktopAppModel(roots).use { model ->
            val op = runBlocking { model.startRealm(includeClient = false) }
            assertFalse("the unseeded temp roots must fail the boot: ${op.detail}", op.ok)
            assertTrue(
                "the spec must have reached the supervisor (preflight, not the launch gate): ${op.detail}",
                "DB-NOT-SEEDED" in op.detail,
            )
            assertFalse(
                "the authorized host must not be refused at the gate: ${op.detail}",
                "launch refused" in op.detail,
            )
        }
    }

    @Test
    fun joinStopsAnActiveLocalRuntimeBeforeJoining() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-join-active"))
        // A controlled non-resolving client directory keeps the join
        // preflight's client leg hermetic (never the dev box's real
        // "Vanilla wow 1.12.1" state).
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LAN_JOIN,
                lanAddress = "192.168.1.50",
                clientDir = File(folder.newFolder("no-client-here"), "missing").absolutePath,
            ),
        )
        seedActiveLocalGeneration(roots)
        DesktopAppModel(roots).use { model ->
            assertEquals(RuntimePhase.WORLD_READY, model.state.value.phase)
            val op = runBlocking { model.startRealm(includeClient = false) }
            // D1: the join preflight is client-prerequisites-only — the
            // unseeded datadir never enters a join's refusal again (the old
            // DB-NOT-SEEDED failure was exactly what D1 removes).
            assertFalse(op.ok)
            assertTrue(
                "the join must refuse on the missing client, not the datadir: ${op.detail}",
                "LAN-JOIN-CLIENT-MISSING" in op.detail && "DB-NOT-SEEDED" !in op.detail,
            )
            assertEquals(
                "the journal must record the join after the auto-stop",
                RuntimeMode.LAN_JOIN,
                model.state.value.runtimeMode,
            )
        }
    }

    /** D1: the LAN_JOIN preflight runs NO server legs — a join on an
     * unseeded machine is decided ENTIRELY by the client leg. The pass
     * shape ("join: client and private LAN endpoint verified") needs a
     * real identity-passing client (the resolver is identity-gated, the
     * same usable-client definition Home uses); what the JVM pins
     * hermetically is that a present-but-unusable client refuses with the
     * D9 raw copy and never with a datadir code. */
    @Test
    fun joinPreflightDecidesOnTheClientAloneNeverTheDatadir() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-join-preflight"))
        val clientDir = folder.newFolder("fake-client")
        File(clientDir, "WoW.exe").writeText("not a real client")
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LAN_JOIN,
                lanAddress = "192.168.1.50",
                clientDir = clientDir.absolutePath,
            ),
        )
        DesktopAppModel(roots).use { model ->
            val preflight = runBlocking {
                model.backend.preflight(
                    com.pocketrealm.supervisor.RuntimeLaunchSpec.lanJoin("local", "192.168.1.50"),
                )
            }
            assertFalse(
                "an unusable client must refuse: ${preflight.detail}",
                preflight.ok,
            )
            assertTrue(
                "the refusal must be the client leg: ${preflight.detail}",
                "LAN-JOIN-CLIENT-MISSING" in preflight.detail,
            )
            assertTrue(
                "the datadir must never enter a join refusal: ${preflight.detail}",
                "DB-NOT-SEEDED" !in preflight.detail &&
                    "DB-MIGRATION-PENDING" !in preflight.detail &&
                    "DB-DIRTY-GENERATION" !in preflight.detail,
            )
        }
    }

    /** D1's LAN_HOST liveness legs (through the internal seam): the stored
     * authorization is re-proved and the endpoint must be a CURRENTLY live
     * private interface (D3-filtered); LOCAL never runs a leg. */
    @Test
    fun lanHostLivenessLegsRefuseTheStaleAddressAndTheMissingAuthorization() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-host-liveness"))
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LAN_HOST,
                lanAddress = "192.168.1.50",
                allowLanPlayers = true,
            ),
        )
        DesktopAppModel(roots).use { model ->
            val spec = com.pocketrealm.supervisor.RuntimeLaunchSpec(
                mode = RuntimeMode.LAN_HOST,
                profileId = DesktopAppModel.DESKTOP_PROFILE,
                endpoint = com.pocketrealm.supervisor.RealmEndpoint.parseLan("192.168.1.50"),
                includeClient = false,
                allowLanPlayers = true,
            )
            val refusal = runBlocking { model.backend.lanHostLivenessRefusal(spec) }
            assertTrue(refusal != null)
            assertTrue(
                "a stored address this PC does not own must refuse with the D9 raw copy: ${refusal!!.detail}",
                "LAN host address is not an active private IPv4 interface" in refusal.detail,
            )
            // LOCAL runs no liveness leg.
            assertNull(
                runBlocking {
                    model.backend.lanHostLivenessRefusal(
                        com.pocketrealm.supervisor.RuntimeLaunchSpec.local(DesktopAppModel.DESKTOP_PROFILE),
                    )
                },
            )
        }
    }

    /** D1: with the stored toggle OFF, the defensive backend twin refuses
     * the host before the interface leg (the model gate's twin). */
    @Test
    fun theBackendsLivenessLegReProvesTheStoredAuthorization() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-host-liveness-off"))
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LAN_HOST,
                lanAddress = "192.168.1.50",
                allowLanPlayers = false,
            ),
        )
        DesktopAppModel(roots).use { model ->
            val spec = com.pocketrealm.supervisor.RuntimeLaunchSpec(
                mode = RuntimeMode.LAN_HOST,
                profileId = DesktopAppModel.DESKTOP_PROFILE,
                endpoint = com.pocketrealm.supervisor.RealmEndpoint.parseLan("192.168.1.50"),
                includeClient = false,
                allowLanPlayers = true,
            )
            val refusal = runBlocking { model.backend.lanHostLivenessRefusal(spec) }
            assertTrue(refusal != null)
            assertTrue(
                "the defensive twin must name the authorization: ${refusal!!.detail}",
                "allowLanPlayers" in refusal.detail,
            )
        }
    }

    /** D1, mode-aware (design-tunnel §10, the Android preflight's twin): a
     * tunnel-advertised start proves the TUNNEL adapter, never the saved
     * LAN address — the tunnel lane hosts even when the stored LAN address
     * is inactive (hotel Wi-Fi, DHCP change, hotspot); a loopback
     * advertisement (the cloudflared recipe) proves nothing. */
    @Test
    fun theTunnelPreflightProvesTheTunnelAdapterNotTheLanAddress() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-host-liveness-tunnel"))
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LAN_HOST,
                lanAddress = "192.168.1.50",
                allowLanPlayers = true,
            ),
        )
        DesktopAppModel(roots).use { model ->
            fun tunnelSpec(advertised: com.pocketrealm.supervisor.AdvertisedAddress) =
                com.pocketrealm.supervisor.RuntimeLaunchSpec(
                    mode = RuntimeMode.LAN_HOST,
                    profileId = DesktopAppModel.DESKTOP_PROFILE,
                    endpoint = com.pocketrealm.supervisor.RealmEndpoint.parseLan("192.168.1.50"),
                    includeClient = false,
                    allowLanPlayers = true,
                    advertisedAddress = advertised,
                )
            // The saved LAN address is the same never-owned address the
            // LAN-leg test pins on, yet a tunnel-advertised spec must not
            // refuse on the LAN leg: it refuses (if at all) on the tunnel
            // adapter only.
            val tunnelRefusal = runBlocking {
                model.backend.lanHostLivenessRefusal(
                    tunnelSpec(com.pocketrealm.supervisor.AdvertisedAddress.parse("100.64.12.34")),
                )
            }
            assertTrue(
                "the tunnel preflight never refuses on the saved LAN address: " +
                    "${tunnelRefusal?.detail}",
                tunnelRefusal == null ||
                    "the tunnel adapter carrying 100.64.12.34 is not up" in tunnelRefusal.detail,
            )
            // Loopback advertisement (cloudflared, wildcard bind): no
            // interface to prove — hosting proceeds to the datadir gates.
            assertNull(
                runBlocking {
                    model.backend.lanHostLivenessRefusal(
                        tunnelSpec(com.pocketrealm.supervisor.AdvertisedAddress.parse("127.0.0.1")),
                    )
                },
            )
        }
    }

    /** The Guests-card ban lane's seeded realmd — the SQLITE provider's
     * database file at the exact path the model's store resolves, with
     * the DesktopGuestBansTest schema and accounts. */
    private fun seedGuestBanRealmd(roots: DesktopStorageRoots) {
        requireNativeIfDemanded(
            "sqlite seam",
            File("..").resolve("native/.build-win-x86_64/sqlite-seam-build/pocket_sqlite.dll"),
        )
        val realmd = com.pocketrealm.database.DatabaseSqliteControlPlane.databaseFile(
            roots.sqliteDatadir,
            com.pocketrealm.server.ServerRuntimeFiles.serverDatabases().realmd,
        )
        realmd.parentFile?.mkdirs()
        com.pocketrealm.database.DesktopSqliteConnection(realmd.absolutePath).use { db ->
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
    }

    /** Every account_banned row in the seeded realmd (active or not — the
     * MariaDB-lane proof must see ZERO writes of any kind). */
    private fun guestBanRowCount(roots: DesktopStorageRoots): Long {
        val realmd = com.pocketrealm.database.DatabaseSqliteControlPlane.databaseFile(
            roots.sqliteDatadir,
            com.pocketrealm.server.ServerRuntimeFiles.serverDatabases().realmd,
        )
        return com.pocketrealm.database.DesktopSqliteConnection(realmd.absolutePath).use { db ->
            db.queryLong("SELECT COUNT(*) FROM account_banned;") ?: 0L
        }
    }

    private fun writeActiveProviderMarker(
        roots: DesktopStorageRoots,
        mode: com.pocketrealm.database.DatabaseDurableState.ProviderMode,
    ) {
        java.io.File(
            roots.database,
            com.pocketrealm.database.DatabaseDurableState.ACTIVE_PROVIDER_MARKER_NAME,
        ).writeText(
            com.pocketrealm.database.DatabaseDurableState.activeProviderMarker(
                mode,
                com.pocketrealm.database.DatabaseRuntimeContract.WINDOWS_PROVIDER_ID,
                "11111111-1111-4111-8111-111111111111",
            ),
        )
    }

    /** F7: the Guests-card ban lane serves BOTH providers. On MARIADB (the
     * shipped default) the verbs ride the provider-agnostic engine relay
     * the Players moderation surface uses — never the SQLite store. With
     * the realm down the relay refuses honestly (the remedy is START THE
     * REALM, never a provider switch), and a seeded SQLITE realmd gains
     * no row at all: the database this lane's realm ignores is never
     * written. The list is the relay's live view — empty while the realm
     * is down (no live view, no list), never the stale SQLite-era rows. */
    @Test
    fun theGuestsBanLaneRidesTheEngineRelayOnTheMariadbProvider() {
        requireNativeIfDemanded(
            "world runtime",
            File("../native/.build-win-x86_64-mariadb/pocket-runtime-build/pocket_world_runtime.dll"),
        )
        val roots = DesktopStorageRoots(folder.newFolder("roots-guest-bans-mariadb"))
        roots.ensureDirectories()
        seedGuestBanRealmd(roots)
        writeActiveProviderMarker(
            roots,
            com.pocketrealm.database.DatabaseDurableState.ProviderMode.MARIADB,
        )
        DesktopAppModel(roots).use { model ->
            listOf(
                "ban" to model.banGuest("Somebody1", "reason"),
                "suspend" to model.suspendGuest("Somebody1", 3_600L, "reason"),
                "unban" to model.unbanGuest("Somebody1"),
            ).forEach { (verb, outcome) ->
                assertTrue(
                    "$verb must refuse while the realm is down: $outcome",
                    outcome is DesktopGuestBans.BanOutcome.Refused,
                )
                val detail = (outcome as DesktopGuestBans.BanOutcome.Refused).detail
                assertTrue(
                    "$verb copy must name the start-the-realm remedy: $detail",
                    "start the realm" in detail,
                )
                assertFalse(
                    "$verb copy must never demand a provider switch: $detail",
                    "SQLite" in detail || "MariaDB" in detail || "switch" in detail,
                )
            }
            assertEquals(
                "the SQLite realmd this lane's realm ignores is never written",
                0L,
                guestBanRowCount(roots),
            )
            assertTrue(
                "no live view while the realm is down: no list",
                model.guestBans().isEmpty(),
            )
        }
    }

    /** F7's other half: an explicit SQLITE selection keeps D5b verbatim —
     * ban, timed suspension, and unban land as DIRECT account_banned rows
     * in the realmd file this provider's realm enforces. */
    @Test
    fun theGuestsBanLaneKeepsTheDirectRowsOnTheSqliteProvider() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-guest-bans-sqlite"))
        roots.ensureDirectories()
        seedGuestBanRealmd(roots)
        writeActiveProviderMarker(
            roots,
            com.pocketrealm.database.DatabaseDurableState.ProviderMode.SQLITE,
        )
        DesktopAppModel(roots).use { model ->
            val ban = model.banGuest("FriendOne", "griefing")
            assertTrue(
                "the direct permanent ban must land: $ban",
                ban is DesktopGuestBans.BanOutcome.Banned && !ban.alreadyBanned,
            )
            assertEquals(7L, (ban as DesktopGuestBans.BanOutcome.Banned).accountId)
            val suspension = model.suspendGuest("FriendTwo", 3_600L, "cool-down")
            assertTrue(
                "the direct timed suspension must land: $suspension",
                suspension is DesktopGuestBans.BanOutcome.Banned,
            )
            val rows = model.guestBans()
            assertEquals(
                "the card's list names both direct rows",
                setOf("FRIENDONE", "FRIENDTWO"),
                rows.map { it.username }.toSet(),
            )
            assertTrue(
                "the permanent/suspension distinction survives (the copy's promise)",
                rows.single { it.username == "FRIENDONE" }.permanent &&
                    !rows.single { it.username == "FRIENDTWO" }.permanent,
            )
            val unban = model.unbanGuest("FriendOne")
            assertTrue(
                "the direct unban must lift the ban: $unban",
                unban is DesktopGuestBans.BanOutcome.Unbanned && unban.wasBanned,
            )
            assertEquals(
                "only the suspension remains",
                listOf("FRIENDTWO"),
                model.guestBans().map { it.username },
            )
        }
    }

    /** F7: the relay's duration render — TimeStringToSecs shape, never
     * unit-less (a bare digit run parses to ZERO seconds, which is the
     * console verb's PERMANENT ban — "3600" would ban the guest forever),
     * d/h/m/s units only, within the relay's 8-char input gate. The
     * card's presets render exactly. */
    @Test
    fun relayBanDurationsRenderInTheEnginesTimeShape() {
        assertEquals("1h", renderRelayBanDuration(3_600L))
        assertEquals("1d", renderRelayBanDuration(86_400L))
        assertEquals("1h30m", renderRelayBanDuration(5_400L))
        assertEquals("45s", renderRelayBanDuration(45L))
        // The 8-char gate drops the smallest components, never the render.
        val bounded = renderRelayBanDuration(9_999_999L)
        assertTrue("the render stays within the relay's gate: $bounded", bounded.length <= 8)
        assertTrue(
            "the render stays parseable (digits and d/h/m/s only): $bounded",
            bounded.all { it.isDigit() || it in "dhms" },
        )
        // An absurd duration whose largest component alone overflows the
        // gate is returned verbatim — never a truncated (different)
        // duration; the engine's own input gate refuses it honestly.
        val absurd = renderRelayBanDuration(Long.MAX_VALUE / 10)
        assertTrue(
            "the oversized render is untruncated and parseable: $absurd",
            absurd.length > 8 && absurd.all { it.isDigit() || it in "dhms" },
        )
        // The permanent duration is the engine's own "0" literal, never a
        // render output.
        assertEquals("0", DesktopAppModel.RELAY_PERMANENT_DURATION)
        assertThrows(IllegalArgumentException::class.java) { renderRelayBanDuration(0L) }
    }

    @Test
    fun localStartOnAnActiveRuntimeIsStillRefused() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-local-active"))
        writeSettings(roots, Settings.Snapshot())
        seedActiveLocalGeneration(roots)
        DesktopAppModel(roots).use { model ->
            assertEquals(RuntimePhase.WORLD_READY, model.state.value.phase)
            val op = runBlocking { model.startRealm(includeClient = false) }
            assertFalse(op.ok)
            assertTrue(
                "the auto-stop is join-only: ${op.detail}",
                "runtime is already active" in op.detail,
            )
        }
    }

    /** A malformed LAN address refuses at the gate in every LAN mode. */
    @Test
    fun aMalformedLanAddressIsRefusedAtTheGate() {
        listOf(RuntimeMode.LAN_HOST, RuntimeMode.LAN_JOIN).forEach { mode ->
            val roots = DesktopStorageRoots(folder.newFolder("roots-bad-${mode.name}"))
            writeSettings(
                roots,
                Settings.Snapshot(runtimeMode = mode, lanAddress = "example.com"),
            )
            DesktopAppModel(roots).use { model ->
                val op = runBlocking { model.startRealm(includeClient = false) }
                assertFalse(op.ok)
                assertTrue(
                    "$mode with a non-IPv4 address must refuse at the gate: ${op.detail}",
                    "launch refused" in op.detail && "IPv4" in op.detail,
                )
            }
        }
    }
}
