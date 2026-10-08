package com.pocketrealm.desktop.realmstatus

import com.pocketrealm.client.ActiveFlavor
import com.pocketrealm.client.ClientFlavors
import com.pocketrealm.client.TwoQualifiedFlavors
import com.pocketrealm.desktop.DesktopAppModel
import com.pocketrealm.desktop.DesktopStorageRoots
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * GAP-3 (owner census UIUX-3, the desktop status leg — the census's one
 * silently-degrading site): with two qualified flavors the hosted
 * realm-status page resolves the ACTIVE lane through the flavor machine.
 * The former `runCatching { qualified().single() }.getOrNull()` collapsed
 * to flavor=null at the second qualification — the world-data cache was
 * never keyed and the page's `realm.flavor` copy named the journaled
 * vanilla default instead of the selected lane. These pins assert the page
 * NAMES the active flavor (wrong copy was the recorded failure mode, so
 * "no crash" alone would not catch a regression) and that the machine's
 * ambiguous no-selection refusal keeps the fail-soft page contract (§7:
 * never a broken page).
 */
class RealmStatusFlavorLaneTest {

    @get:Rule
    val folder = TemporaryFolder()

    @After
    fun restoreRegistryDefaults() {
        ActiveFlavor.reset()
    }

    @Test
    fun realmStatusNamesTheActiveFlavorWhenTwoQualify() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-status-lane"))
        DesktopAppModel(roots).use { model ->
            val controller = model.realmStatus
            TwoQualifiedFlavors.install(selection = ClientFlavors.TBC.flavorId)

            // The world is down on a fresh model, so the page names the
            // ACTIVE lane (the journaled session lane takes over at world
            // start — pageFlavorId's worldUp arm).
            val render = controller.captureRender(hostView = false)
            assertNotNull("the down-state page renders", render)
            assertEquals(
                "the page's realm copy names the SELECTED lane, not the vanilla default",
                ClientFlavors.TBC.flavorId,
                render!!.payload.getJSONObject("realm").getString("flavor"),
            )
        }
    }

    @Test
    fun realmStatusKeepsTheFailSoftPageWhenTheMachineRefusesToNameALane() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-status-ambiguous"))
        DesktopAppModel(roots).use { model ->
            val controller = model.realmStatus
            // Two qualified flavors, NO selection: the machine's honest
            // loud state — the page must stay up (fail-soft, §7), falling
            // back to the journaled lane's copy rather than crashing or
            // inventing a lane.
            TwoQualifiedFlavors.install(selection = null)

            val render = controller.captureRender(hostView = false)
            assertNotNull("the ambiguous state still renders a page", render)
            assertEquals(
                "the fail-soft fallback names the journaled lane, never a silent pick",
                ClientFlavors.VANILLA.flavorId,
                render!!.payload.getJSONObject("realm").getString("flavor"),
            )
        }
    }

    @Test
    fun pageFlavorIdPrefersTheJournaledSessionLaneWhileTheWorldRuns() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-status-session"))
        DesktopAppModel(roots).use { model ->
            val controller = model.realmStatus
            TwoQualifiedFlavors.install(selection = ClientFlavors.TBC.flavorId)

            // The journaled G3-4 session lane is the truth about what is
            // RUNNING; the switcher's selection must not rename a live
            // vanilla session mid-run.
            val running = com.pocketrealm.supervisor.RuntimeSnapshot(
                sessionId = "12345678-1234-1234-1234-123456789abc",
                flavor = ClientFlavors.VANILLA.flavorId,
            )
            assertEquals(
                ClientFlavors.VANILLA.flavorId,
                controller.pageFlavorId(running, worldUp = true),
            )
            assertEquals(
                "world down: the ACTIVE lane names the page",
                ClientFlavors.TBC.flavorId,
                controller.pageFlavorId(running, worldUp = false),
            )
        }
    }
}
