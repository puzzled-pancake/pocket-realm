package com.pocketrealm.desktop

import com.pocketrealm.desktop.ui.FIRST_RUN_TUTORIAL_STEPS
import com.pocketrealm.desktop.ui.tutorialAdvance
import com.pocketrealm.desktop.ui.tutorialBack
import com.pocketrealm.desktop.ui.tutorialVisible
import com.pocketrealm.storage.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * the first-run tutorial's compose-free legs — the
 * visibility gate (shown while `setupComplete` is unset, replayable from
 * the Settings Setup card), the bounded step walk, the step walk's copy,
 * and the model-level gating the register plan pins (tutorial shown iff
 * !setupComplete, sealed through the persisted settings).
 */
class FirstRunTutorialTest {
    @get:Rule
    val folder = TemporaryFolder()

    // ---- pure gate -----------------------------------------------------

    @Test
    fun theGuideShowsUntilSetupSeals() {
        assertTrue(tutorialVisible(setupComplete = false, replayRequests = 0))
        assertFalse(tutorialVisible(setupComplete = true, replayRequests = 0))
    }

    @Test
    fun aReplayRequestOverridesTheSeal() {
        assertTrue(tutorialVisible(setupComplete = true, replayRequests = 1))
        assertTrue(tutorialVisible(setupComplete = false, replayRequests = 3))
    }

    // ---- pure step walk ------------------------------------------------

    @Test
    fun theStepWalkStaysBounded() {
        assertEquals(1, tutorialAdvance(0))
        assertEquals(FIRST_RUN_TUTORIAL_STEPS.lastIndex, tutorialAdvance(FIRST_RUN_TUTORIAL_STEPS.lastIndex))
        assertEquals(0, tutorialBack(0))
        assertEquals(1, tutorialBack(2))
    }

    @Test
    fun theStepsWalkImportPrepareStart() {
        assertEquals(4, FIRST_RUN_TUTORIAL_STEPS.size)
        FIRST_RUN_TUTORIAL_STEPS.forEach { step ->
            assertTrue("blank step copy: ${step.title}", step.title.isNotBlank() && step.body.isNotBlank())
        }
        // Step 2 is the client requirement; step 3 the one-time world data
        // preparation; step 4 the start + account leg (the Android SAF
        // wording has no desktop leg).
        assertTrue("WoW.exe" in FIRST_RUN_TUTORIAL_STEPS[1].body)
        assertTrue("build 5875" in FIRST_RUN_TUTORIAL_STEPS[1].body)
        // the requirement body is derived from
        // the registry's qualified set — every qualified flavor's version
        // and build must be named, and the vanilla MPQ list must not be
        // presented as the generic requirement.
        val qualified = com.pocketrealm.client.ClientFlavors.qualified()
        assertEquals(3, qualified.size)
        for (flavor in qualified) {
            val version = flavor.versionResource.substringBeforeLast(".")
            assertTrue(
                "the requirement body must name the qualified " +
                    "${flavor.flavorId} build: $version / ${flavor.build}",
                version in FIRST_RUN_TUTORIAL_STEPS[1].body &&
                    "build ${flavor.build}" in FIRST_RUN_TUTORIAL_STEPS[1].body,
            )
        }
        assertFalse(
            "the vanilla MPQ enumeration must not be the generic requirement",
            "terrain, texture, wmo" in FIRST_RUN_TUTORIAL_STEPS[1].body,
        )
        assertTrue("world data" in FIRST_RUN_TUTORIAL_STEPS[2].title.lowercase())
        assertTrue("account" in FIRST_RUN_TUTORIAL_STEPS[3].body.lowercase())
    }

    // ---- model-level gating (the register plan's pinned test) ----------

    @Test
    fun aFreshModelShowsTheGuideAndFinishingSealsItPersistently() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-tutorial"))
        DesktopAppModel(roots).use { model ->
            assertTrue(tutorialVisible(model.settings.value.setupComplete, model.tutorialReplayRequests.value))
            model.completeTutorial()
            assertFalse(tutorialVisible(model.settings.value.setupComplete, model.tutorialReplayRequests.value))
            // The seal is the persisted settings field, not UI state.
            val persisted = Settings.Snapshot.fromJson(roots.settingsFile.readText())
            assertTrue(persisted.setupComplete)
        }
    }

    @Test
    fun replayFromSettingsReshowsTheGuideUntilConsumed() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-replay"))
        DesktopAppModel(roots).use { model ->
            model.completeTutorial()
            assertFalse(tutorialVisible(model.settings.value.setupComplete, model.tutorialReplayRequests.value))
            model.replayTutorial()
            assertTrue(tutorialVisible(model.settings.value.setupComplete, model.tutorialReplayRequests.value))
            model.consumeTutorialReplay()
            assertFalse(tutorialVisible(model.settings.value.setupComplete, model.tutorialReplayRequests.value))
        }
    }

    @Test
    fun prepareStepReportsTheSeededDatadirHonestly() {
        val roots = DesktopStorageRoots(folder.newFolder("roots-seed"))
        DesktopAppModel(roots).use { model ->
            // A fresh temp roots has no seeded databases: the tutorial's
            // prepare-world-data status must say so, never fake ready.
            assertFalse(model.datadirSeeded())
        }
    }
}
