package com.pocketrealm.desktop

import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Design-tunnel / / at the app-model level: the stored tunnel
 * advertised address flows into the launch spec (and past the launch gate
 * when the authorization is on), the shared v2 grammar refuses what it
 * must at the gate, the join side accepts the host's tunnel address, and
 * the invite artifact is rendered from the ADVERTISED value — never the
 * LAN IP, never a credential.
 */
class DesktopAppModelTunnelLaunchTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun writeSettings(roots: DesktopStorageRoots, snapshot: Settings.Snapshot) {
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(snapshot.toJson())
    }

    @Test
    fun tunnelHostingWithAuthorizationReachesTheSupervisor() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-tunnel-on"))
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LAN_HOST,
                lanAddress = "192.168.1.50",
                allowLanPlayers = true,
                tunnelAdvertisedAddress = "100.64.12.34",
                // the unseeded-datadir refusal this leg pins is the
                // SQLITE dev/compat lane's; select it explicitly (the
                // shipped default is MariaDB).
                databaseProvider = Settings.DatabaseProvider.SQLITE,
            ),
        )
        DesktopAppModel(roots).use { model ->
            val op = runBlocking { model.startRealm(includeClient = false) }
            assertFalse("the unseeded temp roots must fail the boot: ${op.detail}", op.ok)
            assertTrue(
                "the v2 spec must reach the supervisor (preflight, not the launch gate): ${op.detail}",
                "DB-NOT-SEEDED" in op.detail,
            )
            assertFalse(
                "an authorized tunnel host is not a launch-gate refusal: ${op.detail}",
                "launch refused" in op.detail,
            )
        }
    }

    @Test
    fun malformedTunnelAddressRefusesAtTheGate() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-tunnel-bad"))
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LAN_HOST,
                lanAddress = "192.168.1.50",
                allowLanPlayers = true,
                tunnelAdvertisedAddress = "my-realm.ts.net",
            ),
        )
        DesktopAppModel(roots).use { model ->
            val op = runBlocking { model.startRealm(includeClient = false) }
            assertFalse(op.ok)
            assertTrue(
                "hostnames are not in the v1 grammar: ${op.detail}",
                "launch refused" in op.detail && "IPv4" in op.detail,
            )
        }
    }

    @Test
    fun tunnelAdvertisingWithoutTheHostAuthorizationRefuses() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-tunnel-noauth"))
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LAN_HOST,
                lanAddress = "192.168.1.50",
                allowLanPlayers = false,
                tunnelAdvertisedAddress = "100.64.12.34",
            ),
        )
        DesktopAppModel(roots).use { model ->
            val op = runBlocking { model.startRealm(includeClient = false) }
            assertFalse(op.ok)
            assertTrue(
                "internet exposure is server exposure: ${op.detail}",
                "launch refused" in op.detail && "allowLanPlayers" in op.detail,
            )
        }
    }

    @Test
    fun localModeIgnoresAResidualTunnelAddress() {
        // LOCAL is loopback-only by contract and never reads the tunnel
        // field: a leftover value keeps LOCAL byte-identical — the
        // start proceeds past the launch gate (and refuses later on the
        // unseeded datadir, like any fresh install).
        val roots = DesktopStorageRoots(folder.newFolder("roots-local-tunnel"))
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LOCAL,
                tunnelAdvertisedAddress = "100.64.12.34",
                // the unseeded-datadir refusal this leg pins is the
                // SQLITE dev/compat lane's; select it explicitly (the
                // shipped default is MariaDB).
                databaseProvider = Settings.DatabaseProvider.SQLITE,
            ),
        )
        DesktopAppModel(roots).use { model ->
            val op = runBlocking { model.startRealm(includeClient = false) }
            assertFalse(op.ok)
            assertTrue(
                "LOCAL must not turn a residual tunnel field into a gate refusal: ${op.detail}",
                "DB-NOT-SEEDED" in op.detail && "launch refused" !in op.detail,
            )
            assertNull(
                "the LOCAL spec carries no advertisement",
                model.advertisedOrDefaultForDisplay(),
            )
        }
    }

    @Test
    fun joinAcceptsTheHostsTunnelAddress() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-join-tunnel"))
        writeSettings(
            roots,
            Settings.Snapshot(runtimeMode = RuntimeMode.LAN_JOIN, lanAddress = "100.64.0.9"),
        )
        // The join preflight's client gate resolves through the constructor
        // override lane (WoW.exe presence — the identity gate is the
        // discovered lane's), so a fake client passes here.
        val clientDir = folder.newFolder("fake-join-client")
        java.io.File(clientDir, "WoW.exe").writeText("not a real client")
        DesktopAppModel(roots, clientDirOverride = clientDir).use { model ->
            val op = runBlocking { model.startRealm(includeClient = false) }
            assertFalse(op.ok)
            // The pin is the GRAMMAR leg: a CGNAT join target passes the
            // model's launch gate (the v1 grammar refused it there). The
            // start's downstream legs (client launch infrastructure) fail
            // honestly on this bare fixture — that is later machinery, not
            // the address gate.
            assertTrue(
                "the widened join grammar must not refuse at the gate: ${op.detail}",
                "launch refused" !in op.detail,
            )
            assertTrue(
                "the CGNAT target reached the spec (client-only plan ran): ${op.detail}",
                op.detail.contains("client") || op.detail.contains("Client"),
            )
        }
    }

    @Test
    fun theInviteBlockRendersTheAdvertisedValueFromLiveSettings() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-invite"))
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LAN_HOST,
                lanAddress = "192.168.1.50",
                allowLanPlayers = true,
                tunnelAdvertisedAddress = "100.64.12.34",
            ),
        )
        DesktopAppModel(roots).use { model ->
            val invite = model.tunnelInviteBlock()
            assertTrue("the advertised value leads the invite:\n$invite", "set realmlist 100.64.12.34" in invite)
            assertFalse("never the LAN IP while tunnel mode is on (§5)", "192.168.1.50" in invite)
            assertFalse("never a credential (§6)", "password" in invite.lowercase())
        }
    }

    @Test
    fun plainLanInviteRendersTheLanAddress() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-invite-lan"))
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LAN_HOST,
                lanAddress = "192.168.1.50",
                allowLanPlayers = true,
            ),
        )
        DesktopAppModel(roots).use { model ->
            val invite = model.tunnelInviteBlock()
            assertTrue("the LAN address is the dial target without a tunnel:\n$invite", "set realmlist 192.168.1.50" in invite)
            assertFalse(
                "a plain-LAN invite never tells the friend to install/join a tunnel:\n$invite",
                "ZeroTier" in invite || "Tailscale" in invite || "tunnel network" in invite,
            )
            assertTrue(
                "the LAN invite says the shared network is all that is needed:\n$invite",
                "No tunnel, no installs" in invite,
            )
        }
    }

    @Test
    fun theAdvertisedOrDefaultHelperFollowsTheMode() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-adv-helper"))
        writeSettings(
            roots,
            Settings.Snapshot(
                runtimeMode = RuntimeMode.LAN_HOST,
                lanAddress = "192.168.1.50",
                allowLanPlayers = true,
                tunnelAdvertisedAddress = "100.64.12.34",
            ),
        )
        DesktopAppModel(roots).use { model ->
            assertEquals("100.64.12.34", model.advertisedOrDefaultForDisplay())
        }
    }

    @Test
    fun aFreshModelWithoutTunnelStateHasNoAdvertisedOverride() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-fresh"))
        writeSettings(roots, Settings.Snapshot())
        DesktopAppModel(roots).use { model ->
            assertNull(model.advertisedOrDefaultForDisplay())
        }
    }

    @Test
    fun theMonitorLegsAreModeAware() {
        // the SHARED InterfaceWatch table: the watched leg derives from
        // the SESSION SNAPSHOT's frozen advertisement (never a live settings
        // re-read, which a mid-session edit could re-target) — plain LAN
        // watches the LAN endpoint; a tunnel advertisement watches the
        // tunnel adapter; a loopback advertisement (cloudflared) has no
        // interface to lose. Pure tick decisions, injected seams.
        val plainLan = RuntimeSnapshot(
            sessionId = "s",
            requestedProfile = "local",
            runtimeMode = RuntimeMode.LAN_HOST,
            phase = RuntimePhase.RUNNING,
            clean = false,
            lastDurableAction = "client-window-ready",
            realmEndpoint = com.pocketrealm.supervisor.RealmEndpoint.parseLan("192.168.1.50"),
        )
        val tunnelSession = plainLan.copy(advertisedAddress = "100.64.12.34")
        val cloudflaredSession = plainLan.copy(advertisedAddress = "127.0.0.1")
        // Advertised == bind: any non-loopback advertisement rides the
        // TUNNEL leg per the shared table (the Android monitor's exact
        // decision — no endpoint-equality escape on either platform).
        val advertisedBindSession = plainLan.copy(advertisedAddress = "192.168.1.50")

        fun monitorFor(
            snapshot: RuntimeSnapshot,
            lanLive: () -> Boolean,
            tunnelLive: () -> Boolean,
        ) = LanInterfaceLossMonitor(
            snapshot = { snapshot },
            liveInterface = { lanLive() },
            liveTunnelInterface = { tunnelLive() },
            onGraceElapsed = {},
            clock = { 0L },
            logWarning = {},
        )

        // Loopback advertisement (cloudflared): no interface to lose.
        val cloudflared = monitorFor(cloudflaredSession, { false }, { false })
        assertFalse("cloudflared binds 0.0.0.0 — nothing to lose", cloudflared.tickOnce(cloudflaredSession, 0L))

        // Tunnel advertisement: the tunnel adapter is the watched leg, and
        // physical-LAN churn does not count.
        val tunnelMonitor = monitorFor(tunnelSession, { true }, { false })
        assertTrue(
            "LAN churn with a tunnel advertisement is not loss",
            !tunnelMonitor.tickOnce(tunnelSession, 0L) && !tunnelMonitor.tickOnce(tunnelSession, 9_000_000_000L),
        )
        val fired = tunnelMonitor.tickOnce(tunnelSession, 10_000_000_000L)
        assertTrue("sustained tunnel-adapter loss fires the stop", fired)

        // Plain LAN: the LAN endpoint is the watched leg, and tunnel-adapter
        // state is irrelevant there.
        val lanMonitor = monitorFor(plainLan, { false }, { true })
        assertFalse("the first lost LAN tick opens the grace window", lanMonitor.tickOnce(plainLan, 0L))

        // Advertised == bind watches the tunnel leg on BOTH platforms (the
        // shared decision the two hand-rolled loops used to disagree on).
        val advertisedBindMonitor = monitorFor(advertisedBindSession, { true }, { false })
        assertFalse(
            "advertised == bind rides the tunnel leg, not the LAN leg",
            advertisedBindMonitor.tickOnce(advertisedBindSession, 0L),
        )
    }
}
