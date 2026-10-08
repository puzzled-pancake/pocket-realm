package com.pocketrealm.desktop

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM legs for the DESK-25 spent-world app relaunch's engine: the jpackage
 * image resolution (the launcher exe inside the image dir, resolved through
 * the updater's code-source probe seam) and the honest dev-run refusal. No
 * real process is ever spawned — the spawn seam records or throws.
 */
class DesktopAppRelauncherTest {
    @Test fun relaunchesTheResolvedImageLauncher() {
        val image = Files.createTempDirectory("relaunch-image").toFile()
        val launcher = File(image, "PocketRealm.exe").apply { writeText("launcher") }

        val spawned = mutableListOf<File>()
        val result = DesktopAppRelauncher(
            imageDir = { image },
            spawn = { target -> spawned.add(target); CHILD_PID },
        )
        val success = result.relaunch()
        assertTrue("the spawn must read as success: ${success.detail}", success.ok)
        assertTrue(success.detail.contains("relaunched PocketRealm.exe (pid=$CHILD_PID)"))
        assertEquals(listOf(launcher), spawned)
    }

    @Test fun devRunWithoutAnImageRefusesWithoutSpawning() {
        var spawns = 0
        val result = DesktopAppRelauncher(
            imageDir = { null },
            spawn = { _ -> spawns++; error("must not spawn") },
        )
        val refusal = result.relaunch()
        assertFalse(refusal.ok)
        assertEquals(DesktopAppRelauncher.DEV_RUN_REFUSAL, refusal.detail)
        assertEquals(0, spawns)
    }

    @Test fun imageWithoutTheLauncherRefuses() {
        val image = Files.createTempDirectory("relaunch-empty").toFile()
        val result = DesktopAppRelauncher(imageDir = { image })
        val refusal = result.relaunch()
        assertFalse(refusal.ok)
        assertEquals(DesktopAppRelauncher.DEV_RUN_REFUSAL, refusal.detail)
    }

    @Test fun spawnFailureIsTheHonestRefusal() {
        val image = Files.createTempDirectory("relaunch-fail").toFile()
        File(image, "PocketRealm.exe").writeText("launcher")
        val result = DesktopAppRelauncher(
            imageDir = { image },
            spawn = { _ -> throw java.io.IOException("access denied") },
        )
        val failure = result.relaunch()
        assertFalse(failure.ok)
        assertTrue(failure.detail.contains("app relaunch failed"))
        assertTrue(failure.detail.contains("access denied"))
    }

    /** The production default's resolution seam: UpdateApply's code-source
     * probe returns null off a jpackage image — the same state the
     * dev-run refusal covers. (The real-image leg is the packaged smoke
     * lane; this pins the seam wiring only.) */
    @Test fun productionProbeDefaultsToTheUpdaterResolution() {
        val relauncher = DesktopAppRelauncher(spawn = { CHILD_PID })
        // In a test JVM the code source is a classes dir, never a jpackage
        // image beside PocketRealm.exe — the honest null/refusal state.
        assertNull(UpdateApply.currentAppImageDir())
        assertFalse(relauncher.relaunch().ok)
    }

    private companion object {
        const val CHILD_PID = 4242L
    }
}
