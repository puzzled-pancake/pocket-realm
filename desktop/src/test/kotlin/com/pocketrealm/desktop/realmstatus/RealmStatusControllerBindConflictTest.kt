package com.pocketrealm.desktop.realmstatus

import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.desktop.DesktopSupervisorJournal
import com.pocketrealm.desktop.DesktopAppModel.Companion.DESKTOP_PROFILE
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The controller's BIND-CONFLICT pins at the apply level: with
 * 18080 held by an occupier, a refused transition must EXIT the
 * transition — visibility stays at its prior (served) state, the honest
 * port-naming copy lands, the persisted switch matches, and NO tunnel
 * child spawns. This is the regression pin for the fix-round defect where
 * bindFailureRefusal's result fell through to tunnel.start() + the
 * served-state flip over the refusal.
 *
 * The hosting gate is armed by seeding the supervisor journal with a
 * LAN_HOST snapshot (the machine refuses FRIENDS outside LAN_HOST), the
 * same pattern the launch-gate tests use.
 */
class RealmStatusControllerBindConflictTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** Holds 18080 for the duration of one test (the "other program"). */
    private fun occupy18080(): ServerSocket = ServerSocket().apply {
        bind(InetSocketAddress("127.0.0.1", RealmStatusServer.PAGE_PORT))
    }

    private fun seedLanHostSession(roots: DesktopStorageRoots) {
        DesktopSupervisorJournal(roots.supervisorJournalDir).write(
            RuntimeSnapshot(
                sessionId = "12345678-1234-1234-1234-123456789abc",
                requestedProfile = DESKTOP_PROFILE,
                runtimeMode = RuntimeMode.LAN_HOST,
                phase = RuntimePhase.WORLD_READY,
                clean = true,
                realmEndpoint = RealmEndpoint.parseLan("192.168.1.50"),
                lastDurableAction = "world-ready-client-not-requested",
            ),
        )
    }

    @Test
    fun `a taken 18080 refuses the public flip - prior state kept and no tunnel child`() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-public-conflict"))
        seedLanHostSession(roots)
        writeSettings(roots, Settings.Snapshot())
        val occupier = occupy18080()
        try {
            DesktopAppModel(roots).use { model ->
                val controller = model.realmStatus // boots (persisted OFF, sweep runs)
                controller.setVisibility(RealmStatusVisibility.PUBLIC)
                val ui = controller.uiState.value
                assertEquals(
                    "the refused transition keeps the PRIOR state (OFF)",
                    RealmStatusVisibility.OFF,
                    ui.visibility,
                )
                assertEquals(
                    "the refusal is the machine's bind-conflict reason",
                    RefusalReason.BIND_CONFLICT,
                    ui.refusalReason,
                )
                assertTrue(
                    "the copy names the port, never a pid or path: ${ui.refusalCopy}",
                    ui.refusalCopy?.contains("18080") == true &&
                        !ui.refusalCopy!!.contains(":\\"),
                )
                assertEquals(
                    "the persisted switch matches the served state",
                    RealmStatusVisibility.OFF,
                    model.settings.value.realmStatusVisibility,
                )
                // The regression pin: the refusal must not have fallen
                // through to tunnel.start(). With cloudflared absent on
                // this host a spawned child would land binary-missing —
                // either way the tunnel must not have been STARTED at all.
                assertTrue(
                    "no tunnel child may spawn on a refused transition: ${ui.tunnel}",
                    ui.tunnel.state == RealmStatusPayload.TunnelState.DOWN &&
                        ui.tunnel.url == null,
                )
            }
        } finally {
            occupier.close()
        }
    }

    @Test
    fun `a boot-time friends restore with a taken 18080 degrades to persisted off`() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-boot-conflict"))
        seedLanHostSession(roots)
        writeSettings(
            roots,
            Settings.Snapshot(realmStatusVisibility = RealmStatusVisibility.FRIENDS),
        )
        val occupier = occupy18080()
        try {
            DesktopAppModel(roots).use { model ->
                // First touch boots the controller: the persisted FRIENDS
                // restore attempts the wildcard bind, fails on the
                // occupier, and must degrade to OFF — persisted OFF — with
                // the honest copy. The regression pin: no phantom
                // FRIENDS label on a dead listener, copy intact.
                val ui = model.realmStatus.uiState.value
                assertEquals(
                    "the boot restore degrades to OFF on a bind conflict",
                    RealmStatusVisibility.OFF,
                    ui.visibility,
                )
                assertNotNull(
                    "the boot bind failure lands the honest copy on the card",
                    ui.refusalCopy,
                )
                assertEquals(
                    "the boot failure persists OFF (never a phantom FRIENDS)",
                    RealmStatusVisibility.OFF,
                    model.settings.value.realmStatusVisibility,
                )
            }
        } finally {
            occupier.close()
        }
    }

    private fun writeSettings(roots: DesktopStorageRoots, snapshot: Settings.Snapshot) {
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(snapshot.toJson())
    }
}
