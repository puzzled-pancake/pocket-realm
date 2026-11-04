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
    fun aTbcOnlyImportRegistersAsImportedAndHidesTheTutorialOnceSealed() {
        // the probe enumerates
        // EVERY registered flavor's canonical pointer (active-tbc.json /
        // active-wotlk.json live beside active-vanilla.json) — a tbc-only
        // or wotlk-only import registers, the tutorial hides, and the
        // setupComplete seal can fire. Source contract: the probe's file
        // set is the ENUMERATION (every ClientFlavors.ALL flavor through
        // GenerationPointers.activePointerName), never a hard-coded
        // vanilla pair.
        assertTrue(
            "a tbc-pointer import registers as imported",
            managedClientImported(pointerFileExists = true, legacyDirExists = false),
        )
        // the seal writes once settings emitted (setupComplete == false,
        // meaning "no prepared client") AND the friend dismissed the guide
        assertTrue(tutorialSealWrite(setupComplete = false, dismissed = true))
        val source = locateSource(
            "android/app/src/main/java/com/pocketrealm/ui/PocketRealmApp.kt",
        )
        assertTrue(
            "the probe must enumerate every registered flavor's canonical " +
                "pointer (GenerationPointers.activePointerName over " +
                "ClientFlavors.ALL), never a hard-coded vanilla pair",
            "ClientFlavors.ALL.any" in source &&
                "GenerationPointers.activePointerName(version.flavorId)" in source,
        )
    }

    private fun locateSource(repoRelative: String): String {
        var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            val candidate = dir?.let { java.io.File(it, repoRelative) }
            if (candidate != null && candidate.isFile) return candidate.readText()
            dir = dir?.parentFile
        }
        error("source not found walking up from ${System.getProperty("user.dir")}: $repoRelative")
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
        // the requirement copy is flavor-neutral — the flip made
        // tbc/wotlk qualified, so the tutorial no longer names the vanilla
        // client version as if it were the only lane.
        assertEquals(
            "What you need: an extracted WoW client for your game version",
            FIRST_RUN_TUTORIAL_STEPS[1].title,
        )
        assertEquals("How the selection works", FIRST_RUN_TUTORIAL_STEPS[2].title)
        assertEquals("What happens next", FIRST_RUN_TUTORIAL_STEPS[3].title)
        assertEquals(4, FIRST_RUN_TUTORIAL_STEPS.size)
        FIRST_RUN_TUTORIAL_STEPS.forEach { step ->
            assertTrue(step.title.isNotBlank())
            assertTrue(step.body.isNotBlank())
        }
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
        // the body is derived from the
        // registry's qualified set — every qualified flavor's version and
        // build must be named, and the vanilla MPQ list must not be
        // presented as the generic requirement (the required set differs
        // per game version).
        val qualified = com.pocketrealm.client.ClientFlavors.qualified()
        assertEquals(3, qualified.size)
        for (flavor in qualified) {
            val version = flavor.versionResource.substringBeforeLast(".")
            assertTrue(
                "the requirement body must name the qualified " +
                    "${flavor.flavorId} build: $version / ${flavor.build}",
                version in text && "build ${flavor.build}" in text,
            )
        }
        assertFalse(
            "the vanilla MPQ enumeration must not be the generic requirement",
            "terrain, texture, wmo" in text,
        )
        assertTrue(
            "the body must say the required MPQ set differs per game version",
            "differs per game version" in text,
        )
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
