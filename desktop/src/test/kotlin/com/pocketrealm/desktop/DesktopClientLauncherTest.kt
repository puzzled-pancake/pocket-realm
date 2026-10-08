package com.pocketrealm.desktop

import com.pocketrealm.storage.Settings
import com.pocketrealm.supervisor.RealmEndpoint
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The launcher's client-directory resolution contract: the stored client
 * folder (the Home picker) is the ONLY implicit source. An unconfigured
 * machine refuses honestly instead of falling back to a conventional
 * install — the historical "Vanilla wow 1.12.1" default launched a
 * client the user never chose on any machine that happened to have that
 * folder. The dev lane passes its own -DclientDir (RpHost/LaunchClient).
 */
class DesktopClientLauncherTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** No override + no stored folder → the unconfigured refusal, never a
     * conventional default. Deterministic on EVERY machine precisely
     * because no absolute path is consulted anymore. */
    @Test
    fun unconfiguredMachineRefusesInsteadOfADefaultInstall() {
        // Never written: the store's fresh snapshot (blank clientDir).
        val settings = File(folder.newFolder("roots"), "settings.json")
        val launcher = DesktopClientLauncher(settings, clientDirOverride = null)
        val refusal = runCatching {
            launcher.launch(RealmEndpoint.LOCAL, owner = null)
        }.exceptionOrNull()
        assertTrue(
            "the refusal must be the unconfigured-directory copy: $refusal",
            refusal is IllegalStateException &&
                refusal.message == "client directory not configured (choose the game folder on Home)",
        )
    }

    /** A stored folder without WoW.exe refuses honestly — the broken
     * stored folder never falls through to ANY conventional default
     * (resolution requires the exe, so the refusal is the unconfigured
     * copy naming the Home picker, not a launch from a wrong path). */
    @Test
    fun brokenStoredFolderRefusesNeverFallsThroughToADefault() {
        val roots = folder.newFolder("stored-roots")
        val stored = folder.newFolder("stored-client") // empty: no WoW.exe
        val settings = File(roots, "settings.json")
        DesktopSettingsStore(settings).save(Settings.Snapshot(clientDir = stored.absolutePath))
        val launcher = DesktopClientLauncher(settings, clientDirOverride = null)
        val refusal = runCatching {
            launcher.launch(RealmEndpoint.LOCAL, owner = null)
        }.exceptionOrNull()
        assertTrue(
            "a broken stored folder must refuse, never fall back: $refusal",
            refusal is IllegalStateException &&
                refusal.message == "client directory not configured (choose the game folder on Home)",
        )
    }

    /** The dev lane's -DclientDir override resolves with NO settings and
     * NO default involved — and an override folder without WoW.exe
     * refuses NAMING THAT FOLDER (the refusal order's second leg). */
    @Test
    fun overrideFolderWithoutTheExeRefusesNamingTheOverrideFolder() {
        val settings = File(folder.newFolder("override-roots"), "settings.json")
        val override = folder.newFolder("override-client") // empty: no WoW.exe
        val launcher = DesktopClientLauncher(settings, clientDirOverride = override)
        val refusal = runCatching {
            launcher.launch(RealmEndpoint.LOCAL, owner = null)
        }.exceptionOrNull()
        assertTrue(
            "the refusal must name the override folder: $refusal",
            refusal is IllegalStateException &&
                refusal.message == "WoW.exe not found under ${override.absolutePath}",
        )
    }
}
