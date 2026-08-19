package com.pocketrealm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstRunTutorialTest {

    @Test
    fun tutorialStaysHiddenWhileSettingsOrClientProbeAreUnresolved() {
        assertFalse(tutorialVisible(null, null, 0))
        assertFalse(tutorialVisible(false, null, 0))
        assertFalse(tutorialVisible(null, false, 0))
    }

    @Test
    fun tutorialShowsOnAFreshInstallOnceInputsResolve() {
        assertTrue(tutorialVisible(false, false, 0))
    }

    @Test
    fun tutorialStaysHiddenAfterCompletionOrExistingImport() {
        assertFalse(tutorialVisible(true, false, 0))
        assertFalse(tutorialVisible(false, true, 0))
        assertFalse(tutorialVisible(true, true, 0))
    }

    @Test
    fun replayRequestOverridesEveryGate() {
        assertTrue(tutorialVisible(null, null, 1))
        assertTrue(tutorialVisible(true, false, 2))
    }

    @Test
    fun importedClientProbeMatchesEitherPointerShape() {
        assertTrue(managedClientImported(pointerFileExists = true, legacyDirExists = false))
        assertTrue(managedClientImported(pointerFileExists = false, legacyDirExists = true))
        assertTrue(managedClientImported(pointerFileExists = true, legacyDirExists = true))
        assertFalse(managedClientImported(pointerFileExists = false, legacyDirExists = false))
    }

    @Test
    fun sealWriteRequiresResolvedUnsealedSettingsAndADismissal() {
        assertFalse(tutorialSealWrite(null, true))
        assertFalse(tutorialSealWrite(null, false))
        assertTrue(tutorialSealWrite(false, true))
        assertFalse(tutorialSealWrite(false, false))
        assertFalse(tutorialSealWrite(true, true))
        assertFalse(tutorialSealWrite(true, false))
    }

    @Test
    fun tutorialHasFourOrderedUsableSteps() {
        assertEquals(4, FIRST_RUN_TUTORIAL_STEPS.size)
        assertEquals("Welcome to Pocket Realm", FIRST_RUN_TUTORIAL_STEPS[0].title)
        assertEquals("What you need: an extracted WoW 1.12.1 client", FIRST_RUN_TUTORIAL_STEPS[1].title)
        assertEquals("How the selection works", FIRST_RUN_TUTORIAL_STEPS[2].title)
        assertEquals("What happens next", FIRST_RUN_TUTORIAL_STEPS[3].title)
        FIRST_RUN_TUTORIAL_STEPS.forEach { step ->
            assertTrue(step.title.isNotBlank())
            assertTrue(step.body.isNotBlank())
        }
    }

    @Test
    fun requirementIsVerboseAboutTheUncompressedNonInstallerClient() {
        val text = UNCOMPRESSED_CLIENT_REQUIREMENT.lowercase()
        listOf(
            "1.12.1", "5875", "extract", "uncompressed", "launcher",
            ".zip", ".7z", ".rar", "entitled",
        ).forEach { keyword -> assertTrue(keyword in text) }
        assertTrue("must not be an installer" in text)
        assertTrue("wow.exe" in text)
    }

    @Test
    fun clientPickerAutoOpenIsConsumedExactlyOnce() {
        ClientPickerAutoOpen.pending = true
        assertTrue(ClientPickerAutoOpen.consumeOnce())
        // The flag was cleared by the first consumption, so a recreated
        // composition observes false and cannot launch the picker twice.
        assertFalse(ClientPickerAutoOpen.consumeOnce())
        assertFalse(ClientPickerAutoOpen.pending)
    }
}
