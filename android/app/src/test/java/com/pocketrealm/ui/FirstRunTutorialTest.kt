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
    fun tutorialHasFiveOrderedUsableSteps() {
        assertEquals(5, FIRST_RUN_TUTORIAL_STEPS.size)
        assertEquals("Welcome to Pocket Realm", FIRST_RUN_TUTORIAL_STEPS[0].title)
        assertEquals("What you need: an extracted WoW 1.12.1 client", FIRST_RUN_TUTORIAL_STEPS[1].title)
        assertEquals("How the selection works", FIRST_RUN_TUTORIAL_STEPS[2].title)
        assertEquals("What happens next", FIRST_RUN_TUTORIAL_STEPS[3].title)
        // the optional talking-bots step points at the LLM submenu +
        // the small "try first" model download (a 30-second step)
        assertEquals("Optional: make the people talk back", FIRST_RUN_TUTORIAL_STEPS[4].title)
        FIRST_RUN_TUTORIAL_STEPS.forEach { step ->
            assertTrue(step.title.isNotBlank())
            assertTrue(step.body.isNotBlank())
        }
    }

    @Test
    fun llmStepPointsAtTheSubmenuAndTryFirstModel() {
        val step = FIRST_RUN_TUTORIAL_STEPS[4]
        val text = step.body.lowercase()
        // the submenu's actual entry name in Settings (card "AI bot LLM",
        // button "Configure AI bot LLM →")
        assertTrue("ai bot llm" in text)
        assertTrue("try first" in text)
        // honest about the distribution state: the small tuned model is
        // hand-staged, and the copy must not promise an in-app download
        // of it (that would dead-end the user)
        assertTrue("staged from a pc" in text)
        assertTrue("in-app download" in text)
        // optional, honest about skipping
        assertTrue("optional" in step.title.lowercase())
        assertTrue("skip it" in text)
        // F3 lane honesty: the realm and game are always local; the bot
        // chat is on-device BY DEFAULT and can be pointed at a cloud
        // provider. The old unqualified claim must not come back.
        assertTrue("offline" in text)
        assertTrue("on this device" in text)
        assertTrue("by default" in text)
        assertTrue("cloud provider" in text)
        assertFalse("everything runs on this device" in text)
    }

    @Test
    fun requirementIsVerboseAboutFolderAndArchiveLanes() {
        val text = UNCOMPRESSED_CLIENT_REQUIREMENT.lowercase()
        listOf(
            "1.12.1", "5875", "extract", "uncompressed", "launcher",
            ".zip", ".7z", ".rar", "entitled",
        ).forEach { keyword -> assertTrue(keyword in text) }
        assertTrue("wow.exe" in text)
        assertTrue("can extract it for you" in text)
        assertTrue("password-protected" in text)
        // The original installer archive is a supported payload…
        assertTrue("setup.exe" in text)
        assertTrue("setup-*.bin" in text)
        assertTrue("unpacked on device" in text)
        // …while Windows installers you must run yourself stay refused.
        assertTrue("must not be a windows installer" in text)
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
