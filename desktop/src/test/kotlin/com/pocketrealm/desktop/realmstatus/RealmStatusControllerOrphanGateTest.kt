package com.pocketrealm.desktop.realmstatus

import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.DesktopAppModel.Companion.DESKTOP_PROFILE
import com.pocketrealm.desktop.DesktopStorageRoots
import com.pocketrealm.desktop.DesktopSupervisorJournal
import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RealmEndpoint
import com.pocketrealm.supervisor.RuntimeMode
import com.pocketrealm.supervisor.RuntimePhase
import com.pocketrealm.supervisor.RuntimeSnapshot
import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The §5.3 orphan-gate pins at the APPLY level: the fresh sweep that
 * runs before every binding transition gates THAT transition — an orphan
 * that appears AFTER boot (so the machine read a then-clear gate input)
 * must refuse on the FIRST toggle, not one sweep late. This pins the
 * review finding where applyTransition ran the sweep, tripped the gate,
 * and still proceeded to server.start + the served-state flip — binding
 * 18080 over a live record-less orphan and exposing the FRIENDS page
 * through its quick-tunnel URL.
 *
 * The after-boot timing rides the §5.3 process-table seam (the test
 * override of the production PowerShell probe): the fake table is empty
 * at boot, then gains a record-less cloudflared with the exact
 * page-tunnel tail before the toggle.
 *
 * The acknowledge pin: the §5.3 ack is remembered PER-PID for the app
 * lifetime — the accepts-the-exposure case (the host leaves the
 * record-less orphan live on purpose) must not loop ack → toggle → sweep
 * re-detects → refusal. After an explicit ack the SAME pid never re-trips
 * the gate; a NEW record-less orphan still does.
 */
class RealmStatusControllerOrphanGateTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** The fake §5.3 process table: a mutable backing list so the test
     * can make a record-less orphan APPEAR after boot. */
    private class FakeTable : TunnelOrphanSweep.ProcessTable {
        var entries: List<TunnelOrphanSweep.ProcessEntry> = emptyList()
        override fun cloudflaredProcesses(): List<TunnelOrphanSweep.ProcessEntry> = entries
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

    /** A record-less orphan: the EXACT page-tunnel invocation tail (the
     * production spawn seam's realized line), started before this app
     * instance — the §5.3 older-than-app leg — with no durable record. */
    private fun orphanEntry(pid: Long): TunnelOrphanSweep.ProcessEntry =
        TunnelOrphanSweep.ProcessEntry(
            pid = pid,
            commandLine = TunnelOrphanSweep.realizedCommandLine("E:\\elsewhere\\cloudflared.exe"),
            // An hour before NOW: strictly older than the controller's
            // app start (captured at its construction moments earlier).
            startUnixSeconds = System.currentTimeMillis() / 1_000L - 3_600L,
        )

    /** Binds 18080 on loopback — succeeds only when the controller never
     * bound it (the black-box "no bind happened" probe). */
    private fun assert18080Free() {
        ServerSocket().use { probe ->
            probe.bind(InetSocketAddress("127.0.0.1", RealmStatusServer.PAGE_PORT))
        }
    }

    /** The inverse probe: the loopback bind FAILS only while the
     * controller's page server holds 18080 (the black-box "the bind really
     * happened" check). */
    private fun assert18080Bound() {
        try {
            ServerSocket().use { probe ->
                probe.bind(InetSocketAddress("127.0.0.1", RealmStatusServer.PAGE_PORT))
            }
            throw AssertionError("expected the realm-status page to hold ${RealmStatusServer.PAGE_PORT}")
        } catch (expected: java.net.BindException) {
            // held: the transition really bound the page server
        }
    }

    @Test
    fun `an orphan appearing after boot refuses the first binding toggle - not one sweep late`() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-after-boot-orphan"))
        seedLanHostSession(roots)
        DesktopAppModel(roots).use { model ->
            val table = FakeTable()
            val controller = RealmStatusController(model, table).apply { boot() }
            try {
                // Premise pin: boot swept a CLEAR table — no gate held.
                assertNull(
                    "boot with a clear process table holds no orphan gate",
                    controller.uiState.value.orphanPid,
                )
                // The orphan appears AFTER boot (a record-less
                // cloudflared with the exact page-tunnel tail):
                table.entries = listOf(orphanEntry(pid = 999L))
                // The machine read a THEN-CLEAR gate; the apply-time
                // sweep must gate THIS toggle anyway.
                controller.setVisibility(RealmStatusVisibility.FRIENDS)
                val ui = controller.uiState.value
                assertEquals(
                    "the gated transition keeps the PRIOR state (OFF)",
                    RealmStatusVisibility.OFF,
                    ui.visibility,
                )
                assertEquals(
                    "the refusal is the §5.3 orphan reason",
                    RefusalReason.ORPHAN_PENDING,
                    ui.refusalReason,
                )
                assertTrue(
                    "the copy is the machine's orphan copy: ${ui.refusalCopy}",
                    ui.refusalCopy == RealmVisibilityMachine.ORPHAN_PENDING_COPY,
                )
                assertEquals(
                    "the orphan surfaces on the hosting card",
                    999L,
                    ui.orphanPid,
                )
                assertEquals(
                    "the persisted switch matches the served state",
                    RealmStatusVisibility.OFF,
                    model.settings.value.realmStatusVisibility,
                )
                assert18080Free()
                // The held gate also refuses the NEXT toggle through the
                // machine's own input (no fresh sweep needed).
                controller.setVisibility(RealmStatusVisibility.PUBLIC)
                assertEquals(
                    "the held gate refuses the next binding toggle too",
                    RefusalReason.ORPHAN_PENDING,
                    controller.uiState.value.refusalReason,
                )
                assertEquals(
                    "visibility never flipped",
                    RealmStatusVisibility.OFF,
                    controller.uiState.value.visibility,
                )
                assert18080Free()
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun `a friends restore with an orphan present at boot keeps off under the gate`() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-boot-orphan"))
        seedLanHostSession(roots)
        roots.settingsFile.parentFile?.mkdirs()
        roots.settingsFile.writeText(
            Settings.Snapshot(realmStatusVisibility = RealmStatusVisibility.FRIENDS).toJson(),
        )
        DesktopAppModel(roots).use { model ->
            val table = FakeTable().apply { entries = listOf(orphanEntry(pid = 4242L)) }
            val controller = RealmStatusController(model, table).apply { boot() }
            try {
                // The boot sweep trips the gate, and the persisted FRIENDS
                // restore — a transition that binds 18080 — must refuse
                // under it (fail-closed OFF, honest copy), exactly like a
                // boot-time bind conflict degrades.
                val ui = controller.uiState.value
                assertEquals(
                    "the gated boot restore keeps OFF",
                    RealmStatusVisibility.OFF,
                    ui.visibility,
                )
                assertEquals(
                    "the refusal is the §5.3 orphan reason",
                    RefusalReason.ORPHAN_PENDING,
                    ui.refusalReason,
                )
                assertEquals(
                    "the orphan surfaces on the hosting card",
                    4242L,
                    ui.orphanPid,
                )
                assertEquals(
                    "the persisted switch matches the served state",
                    RealmStatusVisibility.OFF,
                    model.settings.value.realmStatusVisibility,
                )
                assert18080Free()
            } finally {
                controller.close()
            }
        }
    }

    @Test
    fun `an acknowledged orphan does not re-gate the binding toggle - the accepts-the-exposure case`() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-ack-orphan"))
        seedLanHostSession(roots)
        DesktopAppModel(roots).use { model ->
            val table = FakeTable()
            val controller = RealmStatusController(model, table).apply { boot() }
            try {
                // Premise: the orphan that appears after boot gates the
                // first toggle (the apply-time fresh sweep trips the gate).
                table.entries = listOf(orphanEntry(pid = 777L))
                controller.setVisibility(RealmStatusVisibility.FRIENDS)
                assertEquals(
                    "premise: the unacknowledged orphan gates the toggle",
                    RefusalReason.ORPHAN_PENDING,
                    controller.uiState.value.refusalReason,
                )
                // The host ACKNOWLEDGES — accepts the exposure: the pid
                // stays live in the table on purpose.
                controller.acknowledgeOrphan()
                assertNull(
                    "the ack clears the card notice",
                    controller.uiState.value.orphanPid,
                )
                // The SAME pid is still live, so the apply-time sweep
                // re-detects it — but after the explicit ack it must not
                // re-trip the gate: the transition proceeds and binds.
                controller.setVisibility(RealmStatusVisibility.FRIENDS)
                val ui = controller.uiState.value
                assertEquals(
                    "the acknowledged orphan no longer gates the toggle",
                    RealmStatusVisibility.FRIENDS,
                    ui.visibility,
                )
                assertNull("no refusal copy remains", ui.refusalReason)
                assertNull("the acknowledged pid never re-surfaces", ui.orphanPid)
                assertEquals(
                    "the persisted switch matches the served state",
                    RealmStatusVisibility.FRIENDS,
                    model.settings.value.realmStatusVisibility,
                )
                assert18080Bound()
                // The ack is PER-PID: a NEW record-less orphan (the host
                // stopped the first out-of-band) still trips the gate, and
                // the gated PUBLIC keeps the prior FRIENDS listener serving.
                table.entries = listOf(orphanEntry(pid = 888L))
                controller.setVisibility(RealmStatusVisibility.PUBLIC)
                val gated = controller.uiState.value
                assertEquals(
                    "a new record-less orphan re-trips the gate",
                    RefusalReason.ORPHAN_PENDING,
                    gated.refusalReason,
                )
                assertEquals(
                    "the gated transition keeps the PRIOR state (FRIENDS)",
                    RealmStatusVisibility.FRIENDS,
                    gated.visibility,
                )
                assertEquals(
                    "the new orphan surfaces on the hosting card",
                    888L,
                    gated.orphanPid,
                )
                assert18080Bound()
            } finally {
                controller.close()
            }
        }
    }
}
