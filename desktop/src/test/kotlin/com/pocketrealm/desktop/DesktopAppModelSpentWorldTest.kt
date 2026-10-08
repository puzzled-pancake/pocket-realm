package com.pocketrealm.desktop

import com.pocketrealm.supervisor.RuntimePhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The DESK-25 spent-world surface at the app-model level, on a REAL model
 * (supervisor + journal + single-instance lock, the SaveExitDrainTest
 * posture): the spent-lifetime verdict delegates to the backend, and the
 * app-relaunch verb surfaces the relauncher's refusal honestly — the app
 * stays alive (lock still held, model still usable) because a refusal must
 * never drain the session. The success path ends the process inside the
 * verb (exitProcess), so it is exercised at the relauncher seam level in
 * DesktopAppRelauncherTest, not here.
 */
class DesktopAppModelSpentWorldTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test fun freshModelReportsAnUnspentWorldLifetime() {
        DesktopAppModel(DesktopStorageRoots(folder.newFolder("fresh"))).use { model ->
            assertFalse("a fresh model has run no world", model.worldLifetimeSpent())
        }
    }

    @Test fun relaunchRefusalSurfacesHonestlyAndLeavesTheModelAlive() {
        val refusal = DesktopAppRelauncher.RelaunchResult(
            ok = false,
            detail = DesktopAppRelauncher.DEV_RUN_REFUSAL,
        )
        val model = DesktopAppModel(
            DesktopStorageRoots(folder.newFolder("refused")),
            relauncher = DesktopAppRelauncher(
                imageDir = { null },
                spawn = { _ -> error("a refused relaunch must never spawn") },
            ),
        )
        val result = runBlocking { model.relaunchApp() }
        assertEquals(refusal.ok, result.ok)
        assertEquals(refusal.detail, result.detail)
        // The refusal left the model alive and usable: the supervisor still
        // answers, and the stack is at rest (nothing was drained).
        assertEquals(RuntimePhase.STOPPED, model.state.value.phase)
        model.close()
    }
}
